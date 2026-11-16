package com.aitest.chat.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.aitest.chat.settings.AppSettings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 给模型用的联网工具：搜索网页，以及打开搜索结果里的公开页面。
 * 客户端禁止自动跳转，跳转由 fetch 自己处理，这样每一步都能重新检查地址。
 */
public class WebAccessTools {

	/** 必应结果页里，标题链接包在 h2 里。 */
	private static final Pattern BING_LINK = Pattern
		.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/** 必应摘要在 class 以 b_lineclamp 开头的段落里。 */
	private static final Pattern BING_SNIPPET = Pattern.compile("<p class=\"b_lineclamp[^\"]*\"[^>]*>(.*?)</p>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	/** 伪装成普通浏览器，避免搜索页直接返回简化或拒绝页面。 */
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

	/** 不跟随跳转，连接超时 5 秒。跳转次数由 fetch 自己限制。 */
	private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
		.followRedirects(java.net.http.HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(5))
		.build();

	private final AppSettings settings;

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	public WebAccessTools(AppSettings settings) {
		this.settings = settings;
	}

	/**
	 * 按当前设置选择必应或 SearXNG 搜索。
	 * 失败时返回说明文字而不是抛异常，这样模型还能继续回答。
	 */
	@Tool(name = "web_search", description = "搜索互联网。问题涉及最新信息、新闻、日期、价格或你不确定的事实时使用。")
	public String webSearch(@ToolParam(description = "搜索关键词", required = true) String query) {
		String text = query == null ? "" : query.trim();
		if (text.isEmpty() || text.length() > 200) {
			return "搜索关键词不能为空，且不能超过 200 个字符。";
		}
		try {
			return "bing".equals(this.settings.webProvider()) ? searchBing(text) : searchSearxng(text);
		}
		catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return "搜索失败：" + ex.getMessage();
		}
		catch (RuntimeException ex) {
			return "搜索失败：" + ex.getMessage();
		}
	}

	/**
	 * 打开一个 http/https 页面并抽出正文，最多保留 6000 字。
	 * 非文本内容直接拒绝。地址校验失败时把原因原样返回给模型。
	 */
	@Tool(name = "web_fetch", description = "打开 http 或 https 网页并提取正文。只打开 web_search 返回的公开链接。")
	public String webFetch(@ToolParam(description = "要打开的网址", required = true) String url) {
		try {
			URI uri = URI.create(url == null ? "" : url.trim());
			Fetched page = fetch(uri, 3, true);
			if (page.status() < 200 || page.status() >= 300) {
				return "打开失败，状态码 " + page.status();
			}
			String contentType = page.contentType().toLowerCase(Locale.ROOT);
			if (!contentType.contains("html") && !contentType.contains("text") && !contentType.contains("json")
					&& !contentType.isBlank()) {
				return "这个地址不是文本页面：" + contentType;
			}
			String text = toPlainText(page.body());
			if (text.isBlank()) {
				return "页面没有提取到正文。";
			}
			return "来源：" + page.uri() + "\n\n" + limit(text, 6000);
		}
		catch (IllegalArgumentException ex) {
			return ex.getMessage();
		}
		catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return "打开网页失败：" + ex.getMessage();
		}
		catch (RuntimeException ex) {
			return "打开网页失败：" + ex.getMessage();
		}
	}

	/**
	 * 从必应结果 HTML 里抽出标题、链接和摘要。摘要按出现顺序和链接配对。
	 * 跳过非 http 链接，以及必应自己的跳转和搜索链接。
	 */
	static List<SearchHit> extractBing(String html, int limit) {
		Matcher links = BING_LINK.matcher(html);
		Matcher snippets = BING_SNIPPET.matcher(html);
		List<String> snippetList = new ArrayList<>();
		while (snippets.find()) {
			snippetList.add(clean(snippets.group(1)));
		}
		List<SearchHit> hits = new ArrayList<>();
		int snippetIndex = 0;
		while (links.find() && hits.size() < limit) {
			String snippet = snippetIndex < snippetList.size() ? snippetList.get(snippetIndex) : "";
			snippetIndex++;
			String href = links.group(1);
			if (!href.startsWith("http://") && !href.startsWith("https://")) {
				continue;
			}
			if (href.contains("bing.com/ck") || href.contains("bing.com/search")) {
				continue;
			}
			hits.add(new SearchHit(clean(links.group(2)), href, snippet));
		}
		return hits;
	}

	/** 只接受没有用户信息的 http/https 地址。 */
	private static void checkHttp(URI uri) {
		if (uri == null || uri.getHost() == null || uri.getUserInfo() != null) {
			throw new IllegalArgumentException("地址不正确。");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw new IllegalArgumentException("只支持 http 或 https 地址。");
		}
	}

	/** 判断跳转前后是不是同一台主机，忽略大小写。 */
	private static boolean sameHost(URI left, URI right) {
		return left.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost());
	}

	/**
	 * 拒绝本机、内网和链路本地地址，避免模型被诱导去访问内网服务。
	 * 主机名和解析出的每个 IP 都要检查。
	 */
	static void checkPublic(URI uri) {
		checkHttp(uri);
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		if ("localhost".equals(host) || host.endsWith(".local") || host.endsWith(".internal")) {
			throw new IllegalArgumentException("不能访问本机或内网地址。");
		}
		try {
			for (InetAddress address : InetAddress.getAllByName(host)) {
				if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
						|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
					throw new IllegalArgumentException("不能访问本机或内网地址。");
				}
			}
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("无法解析这个网址。");
		}
	}

	/** 请求必应中文搜索页，再从 HTML 里抽结果。 */
	private String searchBing(String query) throws IOException, InterruptedException {
		String url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&setlang=zh-Hans";
		Fetched page = fetch(URI.create(url), 2, true);
		if (page.status() < 200 || page.status() >= 300) {
			return "必应搜索失败，状态码 " + page.status();
		}
		return formatHits(extractBing(page.body(), this.settings.webMaxResults()));
	}

	/**
	 * 请求自建 SearXNG 的 JSON 接口。这里不要求公网地址，因为搜索服务通常跑在本机。
	 * 只保留 http/https 结果，并截到设置的条数。
	 */
	private String searchSearxng(String query) throws IOException, InterruptedException {
		String base = this.settings.webSearchUrl();
		String url = base + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
		Fetched page = fetch(URI.create(url), 2, false);
		if (page.status() < 200 || page.status() >= 300) {
			return "SearXNG 搜索失败，状态码 " + page.status() + "。请检查搜索地址。";
		}
		JsonNode root = this.jsonMapper.readTree(page.body());
		JsonNode results = root.path("results");
		List<SearchHit> hits = new ArrayList<>();
		if (results.isArray()) {
			for (JsonNode item : results) {
				if (hits.size() >= this.settings.webMaxResults()) {
					break;
				}
				String link = item.path("url").asString("");
				if (!link.startsWith("http://") && !link.startsWith("https://")) {
					continue;
				}
				hits.add(new SearchHit(item.path("title").asString(""), link, item.path("content").asString("")));
			}
		}
		return formatHits(hits);
	}

	/**
	 * 发一次 GET。publicOnly 为真时禁止内网；为假时只检查协议，供本机 SearXNG 使用。
	 * 3xx 时手动跟随，最多 redirectsLeft 次。本机搜索服务不允许跳到别的主机。
	 * 正文最多读 1MB，避免大文件占满内存。
	 */
	private Fetched fetch(URI uri, int redirectsLeft, boolean publicOnly) throws IOException, InterruptedException {
		if (publicOnly) {
			checkPublic(uri);
		}
		else {
			checkHttp(uri);
		}
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(10))
			.header("User-Agent", USER_AGENT)
			.header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
			.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
			.GET()
			.build();
		java.net.http.HttpResponse<InputStream> response = HTTP.send(request,
				java.net.http.HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			int status = response.statusCode();
			if (status >= 300 && status < 400 && redirectsLeft > 0) {
				String location = response.headers().firstValue("location").orElse("");
				if (!location.isBlank()) {
					URI next = uri.resolve(location);
					if (!publicOnly && !sameHost(uri, next)) {
						throw new IllegalArgumentException("搜索服务跳转到了其他地址。");
					}
					return fetch(next, redirectsLeft - 1, publicOnly);
				}
			}
			String contentType = response.headers().firstValue("content-type").orElse("");
			Charset charset = charset(contentType);
			byte[] bytes = body.readNBytes(1_000_000);
			return new Fetched(uri, status, contentType, new String(bytes, charset));
		}
	}

	/** 从 Content-Type 里取字符集，缺失或无法识别时用 UTF-8。 */
	private static Charset charset(String contentType) {
		Matcher matcher = Pattern.compile("charset=([^;]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
		if (!matcher.find()) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(matcher.group(1).trim().replace("\"", ""));
		}
		catch (RuntimeException ex) {
			return StandardCharsets.UTF_8;
		}
	}

	/** 把命中结果排成「序号、标题、链接、摘要」，没有结果时返回固定说明。 */
	private static String formatHits(List<SearchHit> hits) {
		if (hits.isEmpty()) {
			return "没有搜到结果。";
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < hits.size(); i++) {
			SearchHit hit = hits.get(i);
			builder.append(i + 1).append(". ").append(hit.title()).append('\n').append(hit.url()).append('\n');
			if (!hit.snippet().isBlank()) {
				builder.append(hit.snippet()).append('\n');
			}
			builder.append('\n');
		}
		return builder.toString().trim();
	}

	/** 去掉 script、style 和标签，只留可读文字。 */
	private static String toPlainText(String html) {
		String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
			.replaceAll("(?is)<style[^>]*>.*?</style>", " ")
			.replaceAll("(?is)<[^>]+>", " ");
		return clean(text);
	}

	/** 去掉残留标签、常见 HTML 实体和多余空白。数字实体直接删掉。 */
	private static String clean(String value) {
		String text = value.replaceAll("(?is)<[^>]+>", " ");
		text = text.replace("&nbsp;", " ").replace("&ensp;", " ").replace("&amp;", "&").replace("&lt;", "<")
			.replace("&gt;", ">").replace("&quot;", "\"").replace("&#183;", "·").replace("&#0183;", "·");
		text = text.replaceAll("&#(\\d+);", "");
		text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
		text = text.replaceAll(" *\\n *", "\n");
		text = text.replaceAll("\\n{3,}", "\n\n");
		return text.trim();
	}

	/** 超过 max 时截断并加上省略号。 */
	private static String limit(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max) + "\n…";
	}

	/** 一次 HTTP 响应：最终地址、状态码、类型和正文。 */
	private record Fetched(URI uri, int status, String contentType, String body) {
	}

	/** 一条搜索结果。 */
	record SearchHit(String title, String url, String snippet) {
	}

}
