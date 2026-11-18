package com.aitest.chat.llm.ollama;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aitest.chat.settings.SettingsService;

import tools.jackson.databind.json.JsonMapper;

/** Ollama 可拉取模型目录：内置 catalog + 可选在线刷新 library 页面。 */
@Service
public class OllamaLibraryService {

	private static final Logger log = LoggerFactory.getLogger(OllamaLibraryService.class);

	private static final Pattern LIBRARY_HREF = Pattern.compile("href=\"/library/([a-zA-Z0-9._-]+)\"", Pattern.CASE_INSENSITIVE);

	private final JsonMapper jsonMapper;

	private final SettingsService settingsService;

	private final OllamaAdminClient adminClient;

	private final List<CatalogItem> bundled;

	public OllamaLibraryService(JsonMapper jsonMapper, SettingsService settingsService, OllamaAdminClient adminClient) {
		this.jsonMapper = jsonMapper;
		this.settingsService = settingsService;
		this.adminClient = adminClient;
		this.bundled = loadBundled();
	}

	/** 合并内置目录、在线 library slug，并标记本地是否已安装。 */
	public Map<String, Object> library(boolean refreshOnline) {
		Map<String, CatalogItem> byName = new LinkedHashMap<>();
		for (CatalogItem item : this.bundled) {
			byName.put(normalizeKey(item.name()), item);
		}
		// 已取消联网刷新 library（避免自动下载/抓取）；仅使用内置目录。refreshOnline 参数保留兼容，忽略。
		if (Boolean.FALSE.equals(refreshOnline)) {
			/* no-op: 永不在线拉取 */
		}
		Set<String> installed = installedNames();
		List<Map<String, Object>> items = new ArrayList<>();
		for (CatalogItem item : byName.values()) {
			LinkedHashMap<String, Object> row = new LinkedHashMap<>();
			row.put("name", item.name());
			row.put("description", item.description());
			row.put("category", item.category());
			row.put("sizeHint", item.sizeHint());
			row.put("installed", isInstalled(item.name(), installed));
			items.add(row);
		}
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("items", items);
		return body;
	}

	private Set<String> installedNames() {
		String baseUrl = this.settingsService.get().baseUrl();
		OllamaInfo info = this.adminClient.inspect(baseUrl);
		LinkedHashSet<String> names = new LinkedHashSet<>();
		if (info.models() != null) {
			for (OllamaModel model : info.models()) {
				if (model.name() != null && !model.name().isBlank()) {
					names.add(model.name().trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		return names;
	}

	private static boolean isInstalled(String name, Set<String> installed) {
		String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
		if (key.isEmpty()) {
			return false;
		}
		if (installed.contains(key)) {
			return true;
		}
		// qwen2.5 匹配已安装的 qwen2.5:7b / qwen2.5:latest
		String base = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
		for (String local : installed) {
			String localBase = local.contains(":") ? local.substring(0, local.indexOf(':')) : local;
			if (local.equals(key) || localBase.equals(base) && (key.equals(base) || key.equals(base + ":latest"))) {
				return true;
			}
			if (local.startsWith(base + ":") && key.equals(base)) {
				return true;
			}
		}
		return false;
	}

	private List<String> fetchRemoteSlugs() {
		try {
			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL)
					.build();
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://ollama.com/library"))
				.timeout(Duration.ofSeconds(15))
				.header("User-Agent", "OllamaChat/1.0")
				.GET()
				.build();
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null) {
				return List.of();
			}
			LinkedHashSet<String> slugs = new LinkedHashSet<>();
			Matcher matcher = LIBRARY_HREF.matcher(response.body());
			while (matcher.find()) {
				String slug = matcher.group(1);
				if (slug != null && !slug.isBlank() && !slug.contains("/")) {
					slugs.add(slug.trim());
				}
			}
			log.info("已从 ollama.com/library 刷新 {} 个模型名", slugs.size());
			return List.copyOf(slugs);
		}
		catch (Exception ex) {
			log.warn("刷新 Ollama library 失败：{}", ex.getMessage());
			return List.of();
		}
	}

	@SuppressWarnings("unchecked")
	private List<CatalogItem> loadBundled() {
		try (InputStream in = OllamaLibraryService.class.getResourceAsStream("/ollama-library-catalog.json")) {
			if (in == null) {
				return List.of();
			}
			Map<String, Object> root = this.jsonMapper.readValue(in, Map.class);
			Object raw = root.get("items");
			if (!(raw instanceof List<?> list)) {
				return List.of();
			}
			List<CatalogItem> items = new ArrayList<>();
			for (Object row : list) {
				if (!(row instanceof Map<?, ?> map)) {
					continue;
				}
				String name = text(map, "name");
				if (name.isBlank()) {
					continue;
				}
				items.add(new CatalogItem(name, text(map, "description"), text(map, "category"), text(map, "sizeHint")));
			}
			return List.copyOf(items);
		}
		catch (Exception ex) {
			log.warn("读取内置模型目录失败：{}", ex.getMessage());
			return List.of();
		}
	}

	private static String guessCategory(String slug) {
		String lower = slug.toLowerCase(Locale.ROOT);
		if (lower.contains("embed") || lower.contains("bge") || lower.contains("minilm") || lower.contains("nomic")) {
			return "embed";
		}
		if (lower.contains("vision") || lower.contains("llava") || lower.contains("vl") || lower.contains("minicpm-v")) {
			return "vision";
		}
		return "chat";
	}

	private static String normalizeKey(String name) {
		return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
	}

	private static String text(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value == null ? "" : String.valueOf(value).trim();
	}

	private record CatalogItem(String name, String description, String category, String sizeHint) {
	}

}
