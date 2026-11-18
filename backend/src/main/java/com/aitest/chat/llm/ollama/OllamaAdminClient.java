package com.aitest.chat.llm.ollama;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aitest.chat.conversation.OllamaErrorMessages;

import tools.jackson.databind.json.JsonMapper;

/** 调用 Ollama 管理接口：探测、列表、拉取、删除模型。 */
@Component
public class OllamaAdminClient {

	private static final Logger log = LoggerFactory.getLogger(OllamaAdminClient.class);

	private final JsonMapper jsonMapper;

	private final RestClient restClient;

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public OllamaAdminClient(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(8));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	public OllamaInfo inspect(String baseUrl) {
		try {
			String version = text(readMap(baseUrl + "/api/version"), "version");
			List<OllamaModel> models = readModels(baseUrl);
			log.info("Ollama 已连接 url={} version={} models={}", baseUrl, version, models.size());
			return new OllamaInfo(true, baseUrl, version, "已连接", models);
		}
		catch (RuntimeException ex) {
			log.warn("Ollama 连接失败 url={}: {}", baseUrl, ex.getMessage());
			return new OllamaInfo(false, baseUrl, "", OllamaErrorMessages.connectionFriendly(ex), List.of());
		}
	}

	/** 流式拉取模型，逐行回调 status 文案。 */
	@SuppressWarnings("unchecked")
	public void pull(String baseUrl, String model, Consumer<String> onStatus) {
		String name = model == null ? "" : model.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("模型名称不能为空");
		}
		try {
			String body = this.jsonMapper.writeValueAsString(Map.of("name", name, "stream", true));
			HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/api/pull"))
				.timeout(Duration.ofHours(2))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<java.io.InputStream> response = this.httpClient.send(request,
					HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() >= 400) {
				throw new IllegalStateException("拉取失败 HTTP " + response.statusCode());
			}
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isBlank()) {
						continue;
					}
					Map<String, Object> map = this.jsonMapper.readValue(line, Map.class);
					Object err = map.get("error");
					if (err != null && !String.valueOf(err).isBlank()) {
						throw new IllegalStateException(String.valueOf(err));
					}
					String status = text(map, "status");
					Object completed = map.get("completed");
					Object total = map.get("total");
					if (completed instanceof Number c && total instanceof Number t && t.longValue() > 0) {
						long pct = Math.min(100, c.longValue() * 100 / t.longValue());
						status = (status.isBlank() ? "downloading" : status) + " " + pct + "%";
					}
					if (!status.isBlank() && onStatus != null) {
						onStatus.accept(status);
					}
				}
			}
			log.info("模型拉取完成 model={}", name);
		}
		catch (IllegalArgumentException | IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("拉取模型失败：" + ex.getMessage(), ex);
		}
	}

	/** 删除本地模型。 */
	public void delete(String baseUrl, String model) {
		String name = model == null ? "" : model.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("模型名称不能为空");
		}
		try {
			String body = this.jsonMapper.writeValueAsString(Map.of("name", name));
			HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/api/delete"))
				.timeout(Duration.ofMinutes(2))
				.header("Content-Type", "application/json")
				.method("DELETE", HttpRequest.BodyPublishers.ofString(body))
				.build();
			HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new IllegalStateException("删除失败 HTTP " + response.statusCode()
						+ (response.body() == null || response.body().isBlank() ? "" : (": " + response.body())));
			}
			log.info("已删除模型 model={}", name);
		}
		catch (IllegalArgumentException | IllegalStateException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("删除模型失败：" + ex.getMessage(), ex);
		}
	}

	private List<OllamaModel> readModels(String baseUrl) {
		Object rawModels = readMap(baseUrl + "/api/tags").get("models");
		if (!(rawModels instanceof List<?> models)) {
			return List.of();
		}
		List<OllamaModel> result = new ArrayList<>();
		for (Object item : models) {
			if (item instanceof Map<?, ?> model) {
				result.add(toModel(model));
			}
		}
		result.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
		return result;
	}

	private OllamaModel toModel(Map<?, ?> model) {
		Map<?, ?> details = model.get("details") instanceof Map<?, ?> map ? map : Map.of();
		return new OllamaModel(text(model, "name"), text(details, "parameter_size"),
				text(details, "quantization_level"), text(details, "family"), longValue(model.get("size")),
				longValue(details.get("context_length")));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readMap(String url) {
		String body = this.restClient.get().uri(URI.create(url)).retrieve().body(String.class);
		if (body == null || body.isBlank()) {
			return Map.of();
		}
		return this.jsonMapper.readValue(body, Map.class);
	}

	private static String text(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value == null ? "" : value.toString();
	}

	private static Long longValue(Object value) {
		return value instanceof Number number ? number.longValue() : null;
	}

	private static String trimSlash(String baseUrl) {
		String value = baseUrl == null ? "" : baseUrl.trim();
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		return value;
	}

}
