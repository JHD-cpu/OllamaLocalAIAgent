package com.aitest.chat.tools.web;

import com.aitest.chat.settings.AppSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 联网搜索与抓取网页正文，并登记来源供前端展示。 */
public class WebAccessTools {
   private static final Logger log = LoggerFactory.getLogger(WebAccessTools.class);
   private static final Pattern BING_LINK = Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", 34);
   private static final Pattern BING_SNIPPET = Pattern.compile("<p class=\"b_lineclamp[^\"]*\"[^>]*>(.*?)</p>", 34);
   private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
   private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(Redirect.NEVER).connectTimeout(Duration.ofSeconds(5L)).build();
   private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
   private final AppSettings settings;
   private final SearchedPages pages;
   private final SearchQueryRewriter queryRewriter;
   private final JsonMapper jsonMapper = JsonMapper.builder().build();

   /** 绑定搜索配置、来源收集器与查询改写器。 */
   public WebAccessTools(AppSettings settings, SearchedPages pages, SearchQueryRewriter queryRewriter) {
      this.settings = settings;
      this.pages = pages;
      this.queryRewriter = queryRewriter;
   }

   /** 按设置走必应或 SearXNG，合并去重后返回摘要列表。 */
   @Tool(
      name = "web_search",
      description = "搜索互联网。问题涉及最新信息、新闻、日期、价格或你不确定的事实时使用。"
   )
   public String webSearch(@ToolParam(description = "搜索关键词",required = true) String query) {
      String text = query == null ? "" : query.trim();
      if (!text.isEmpty() && text.length() <= 200) {
         List<String> queries = this.queryRewriter.rewrite(this.settings, text);
         log.info("搜索 provider={} queries={}", this.settings.webProvider(), queries);
         List<WebAccessTools.SearchHit> merged = new ArrayList<>();
         LinkedHashSet<String> seen = new LinkedHashSet<>();
         String failure = null;

         for (String searchQuery : queries) {
            try {
               List<WebAccessTools.SearchHit> hits = "bing".equals(this.settings.webProvider())
                  ? this.searchBing(searchQuery)
                  : this.searchSearxng(searchQuery);
               log.info("搜索完成 query=\"{}\" hits={}", searchQuery, hits.size());

               for (WebAccessTools.SearchHit hit : hits) {
                  if (hit.url() != null && seen.add(hit.url())) {
                     merged.add(hit);
                     if (merged.size() >= this.settings.webMaxResults()) {
                        break;
                     }
                  }
               }
            } catch (InterruptedException | IOException var12) {
               if (var12 instanceof InterruptedException) {
                  Thread.currentThread().interrupt();
               }

               failure = "搜索失败：" + var12.getMessage();
               log.warn("搜索失败 query=\"{}\"", searchQuery, var12);
               if (var12 instanceof InterruptedException) {
                  break;
               }
            } catch (RuntimeException var13) {
               failure = var13.getMessage() == null ? "搜索失败。" : var13.getMessage();
               log.warn("搜索失败 query=\"{}\"", searchQuery, var13);
            }

            if (merged.size() >= this.settings.webMaxResults()) {
               break;
            }
         }

         if (merged.isEmpty()) {
            return failure == null ? "没有搜到结果。" : failure;
         } else {
            this.remember(merged);
            return "实际搜索：" + String.join("；", queries) + "\n\n" + formatHits(merged);
         }
      } else {
         return "搜索关键词不能为空，且不能超过 200 个字符。";
      }
   }

   /** 拉取公网页面并提取纯文本，限制长度后登记来源。 */
   @Tool(
      name = "web_fetch",
      description = "打开 http 或 https 网页并提取正文。只打开 web_search 返回的公开链接。"
   )
   public String webFetch(@ToolParam(description = "要打开的网址",required = true) String url) {
      String target = url == null ? "" : url.trim();
      log.info("打开网页 url={}", target);

      try {
         URI uri = URI.create(target);
         WebAccessTools.Fetched page = this.fetch(uri, 3, true);
         if (page.status() >= 200 && page.status() < 300) {
            String contentType = page.contentType().toLowerCase(Locale.ROOT);
            if (!contentType.contains("html") && !contentType.contains("text") && !contentType.contains("json") && !contentType.isBlank()) {
               log.info("打开网页不是文本 contentType={} url={}", contentType, page.uri());
               return "这个地址不是文本页面：" + contentType;
            } else {
               String text = toPlainText(page.body());
               if (text.isBlank()) {
                  log.info("打开网页没有正文 status={} url={}", page.status(), page.uri());
                  return "页面没有提取到正文。";
               } else {
                  String limited = limit(text, 6000);
                  String title = htmlTitle(page.body());
                  this.pages.add(title, page.uri().toString());
                  log.info("打开网页完成 status={} chars={} title=\"{}\" url={}", new Object[]{page.status(), limited.length(), title, page.uri()});
                  return "来源：" + page.uri() + "\n\n" + limited;
               }
            }
         } else {
            log.info("打开网页失败 status={} url={}", page.status(), page.uri());
            return "打开失败，状态码 " + page.status();
         }
      } catch (IllegalArgumentException var9) {
         log.warn("打开网页被拒绝 url={}: {}", target, var9.getMessage());
         return var9.getMessage();
      } catch (InterruptedException | IOException var10) {
         if (var10 instanceof InterruptedException) {
            Thread.currentThread().interrupt();
         }

         log.warn("打开网页失败 url={}", target, var10);
         return "打开网页失败：" + var10.getMessage();
      } catch (RuntimeException var11) {
         log.warn("打开网页失败 url={}", target, var11);
         return "打开网页失败：" + var11.getMessage();
      }
   }

   /** 从必应结果 HTML 解析标题、链接与摘要。 */
   static List<WebAccessTools.SearchHit> extractBing(String html, int limit) {
      Matcher links = BING_LINK.matcher(html);
      Matcher snippets = BING_SNIPPET.matcher(html);
      List<String> snippetList = new ArrayList<>();

      while (snippets.find()) {
         snippetList.add(clean(snippets.group(1)));
      }

      List<WebAccessTools.SearchHit> hits = new ArrayList<>();
      int snippetIndex = 0;

      while (links.find() && hits.size() < limit) {
         String snippet = snippetIndex < snippetList.size() ? snippetList.get(snippetIndex) : "";
         snippetIndex++;
         String href = links.group(1);
         if ((href.startsWith("http://") || href.startsWith("https://")) && !href.contains("bing.com/ck") && !href.contains("bing.com/search")) {
            hits.add(new WebAccessTools.SearchHit(clean(links.group(2)), href, snippet));
         }
      }

      return hits;
   }

   /** 校验 URI 为合法的 http(s) 地址。 */
   private static void checkHttp(URI uri) {
      if (uri != null && uri.getHost() != null && uri.getUserInfo() == null) {
         String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
         if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("只支持 http 或 https 地址。");
         }
      } else {
         throw new IllegalArgumentException("地址不正确。");
      }
   }

   /** 比较两个 URI 的主机名是否相同（忽略大小写）。 */
   private static boolean sameHost(URI left, URI right) {
      return left.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost());
   }

   /** 拒绝本机、内网与无法解析为公网的地址（SSRF 防护）。 */
   static void checkPublic(URI uri) {
      checkHttp(uri);
      String host = uri.getHost().toLowerCase(Locale.ROOT);
      if (!"localhost".equals(host) && !host.endsWith(".local") && !host.endsWith(".internal")) {
         try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
               if (address.isAnyLocalAddress()
                  || address.isLoopbackAddress()
                  || address.isLinkLocalAddress()
                  || address.isSiteLocalAddress()
                  || address.isMulticastAddress()) {
                  throw new IllegalArgumentException("不能访问本机或内网地址。");
               }
            }
         } catch (IOException var6) {
            throw new IllegalArgumentException("无法解析这个网址。");
         }
      } else {
         throw new IllegalArgumentException("不能访问本机或内网地址。");
      }
   }

   /** 请求必应 HTML 并解析搜索命中。 */
   private List<WebAccessTools.SearchHit> searchBing(String query) throws IOException, InterruptedException {
      String url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&setlang=zh-Hans";
      WebAccessTools.Fetched page = this.fetch(URI.create(url), 2, true);
      if (page.status() >= 200 && page.status() < 300) {
         return extractBing(page.body(), this.settings.webMaxResults());
      } else {
         throw new IllegalStateException("必应搜索失败，状态码 " + page.status());
      }
   }

   /** 请求 SearXNG JSON 接口并解析 results。 */
   private List<WebAccessTools.SearchHit> searchSearxng(String query) throws IOException, InterruptedException {
      String base = this.settings.webSearchUrl();
      String url = base + "/search?format=json&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
      WebAccessTools.Fetched page = this.fetch(URI.create(url), 2, false);
      if (page.status() >= 200 && page.status() < 300) {
         JsonNode root = this.jsonMapper.readTree(page.body());
         JsonNode results = root.path("results");
         List<WebAccessTools.SearchHit> hits = new ArrayList<>();
         if (results.isArray()) {
            for (JsonNode item : results) {
               if (hits.size() >= this.settings.webMaxResults()) {
                  break;
               }

               String link = item.path("url").asString("");
               if (link.startsWith("http://") || link.startsWith("https://")) {
                  hits.add(new WebAccessTools.SearchHit(item.path("title").asString(""), link, item.path("content").asString("")));
               }
            }
         }

         return hits;
      } else {
         throw new IllegalStateException("SearXNG 搜索失败，状态码 " + page.status() + "。请检查搜索地址。");
      }
   }

   /** 把搜索命中写入 {@link SearchedPages} 供 SSE 推送。 */
   private void remember(List<WebAccessTools.SearchHit> hits) {
      for (WebAccessTools.SearchHit hit : hits) {
         this.pages.add(hit.title(), hit.url());
      }

      log.info("记录搜索结果 {} 条", hits.size());
   }

   /** 从 HTML 中提取 {@code <title>} 文本。 */
   private static String htmlTitle(String html) {
      Matcher matcher = HTML_TITLE.matcher(html);
      return matcher.find() ? clean(matcher.group(1)) : "";
   }

   /** 发送 GET 请求，可选公网校验并跟随有限次重定向。 */
   private WebAccessTools.Fetched fetch(URI uri, int redirectsLeft, boolean publicOnly) throws IOException, InterruptedException {
      if (publicOnly) {
         checkPublic(uri);
      } else {
         checkHttp(uri);
      }

      HttpRequest request = HttpRequest.newBuilder(uri)
         .timeout(Duration.ofSeconds(10L))
         .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
         .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
         .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
         .GET()
         .build();
      HttpResponse<InputStream> response = HTTP.send(request, BodyHandlers.ofInputStream());

      WebAccessTools.Fetched var11;
      try (InputStream body = response.body()) {
         int status = response.statusCode();
         if (status >= 300 && status < 400 && redirectsLeft > 0) {
            String location = response.headers().firstValue("location").orElse("");
            if (!location.isBlank()) {
               URI next = uri.resolve(location);
               if (!publicOnly && !sameHost(uri, next)) {
                  throw new IllegalArgumentException("搜索服务跳转到了其他地址。");
               }

               return this.fetch(next, redirectsLeft - 1, publicOnly);
            }
         }

         String contentType = response.headers().firstValue("content-type").orElse("");
         Charset charset = charset(contentType);
         byte[] bytes = body.readNBytes(1000000);
         var11 = new WebAccessTools.Fetched(uri, status, contentType, new String(bytes, charset));
      }

      return var11;
   }

   /** 从 Content-Type 解析字符集，默认 UTF-8。 */
   private static Charset charset(String contentType) {
      Matcher matcher = Pattern.compile("charset=([^;]+)", 2).matcher(contentType);
      if (!matcher.find()) {
         return StandardCharsets.UTF_8;
      } else {
         try {
            return Charset.forName(matcher.group(1).trim().replace("\"", ""));
         } catch (RuntimeException var3) {
            return StandardCharsets.UTF_8;
         }
      }
   }

   /** 将搜索命中格式化为编号列表文本。 */
   private static String formatHits(List<WebAccessTools.SearchHit> hits) {
      if (hits.isEmpty()) {
         return "没有搜到结果。";
      } else {
         StringBuilder builder = new StringBuilder();

         for (int i = 0; i < hits.size(); i++) {
            WebAccessTools.SearchHit hit = hits.get(i);
            builder.append(i + 1).append(". ").append(hit.title()).append('\n').append(hit.url()).append('\n');
            if (!hit.snippet().isBlank()) {
               builder.append(hit.snippet()).append('\n');
            }

            builder.append('\n');
         }

         return builder.toString().trim();
      }
   }

   /** 去掉脚本/样式与标签，得到粗略纯文本。 */
   private static String toPlainText(String html) {
      String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ").replaceAll("(?is)<style[^>]*>.*?</style>", " ").replaceAll("(?is)<[^>]+>", " ");
      return clean(text);
   }

   /** 解码常见 HTML 实体并规整空白。 */
   private static String clean(String value) {
      String text = value.replaceAll("(?is)<[^>]+>", " ");
      text = text.replace("&nbsp;", " ")
         .replace("&ensp;", " ")
         .replace("&amp;", "&")
         .replace("&lt;", "<")
         .replace("&gt;", ">")
         .replace("&quot;", "\"")
         .replace("&#183;", "·")
         .replace("&#0183;", "·");
      text = text.replaceAll("&#(\\d+);", "");
      text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
      text = text.replaceAll(" *\\n *", "\n");
      text = text.replaceAll("\\n{3,}", "\n\n");
      return text.trim();
   }

   /** 超长文本截断并追加省略标记。 */
   private static String limit(String value, int max) {
      return value.length() <= max ? value : value.substring(0, max) + "\n…";
   }

   /** HTTP 响应的状态、类型与正文。 */
   private record Fetched(URI uri, int status, String contentType, String body) {
   }

   /** 单条搜索结果：标题、URL 与摘要。 */
   record SearchHit(String title, String url, String snippet) {
   }
}
