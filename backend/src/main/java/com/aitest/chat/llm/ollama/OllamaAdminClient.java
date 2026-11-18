package com.aitest.chat.llm.ollama;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aitest.chat.conversation.OllamaErrorMessages;

import tools.jackson.databind.json.JsonMapper;

/** 调用 Ollama 管理接口，探测连接并列出本地模型。 */
@Component
public class OllamaAdminClient {

	private static final Logger log = LoggerFactory.getLogger(OllamaAdminClient.class);

	private final JsonMapper jsonMapper;

	private final RestClient restClient;

	/** 配置较短超时的 REST 客户端。 */
	public OllamaAdminClient(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(8));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/** 读取版本与模型列表；失败时返回带友好说明的结果。 */
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

	/** 解析 {@code /api/tags} 响应并按名称排序。 */
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

	/** 将单条模型 JSON 映射为 {@link OllamaModel}。 */
	private OllamaModel toModel(Map<?, ?> model) {
		Map<?, ?> details = model.get("details") instanceof Map<?, ?> map ? map : Map.of();
		return new OllamaModel(text(model, "name"), text(details, "parameter_size"),
				text(details, "quantization_level"), text(details, "family"), longValue(model.get("size")),
				longValue(details.get("context_length")));
	}

	/** GET 指定 URL 并反序列化为 Map。 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> readMap(String url) {
		String body = this.restClient.get().uri(URI.create(url)).retrieve().body(String.class);
		if (body == null || body.isBlank()) {
			return Map.of();
		}
		return this.jsonMapper.readValue(body, Map.class);
	}

	/** 安全取 Map 中字符串字段。 */
	private static String text(Map<?, ?> map, String key) {
		Object value = map.get(key);
		return value == null ? "" : value.toString();
	}

	/** 将数值对象转为 Long，非数字则 null。 */
	private static Long longValue(Object value) {
		return value instanceof Number number ? number.longValue() : null;
	}

}
