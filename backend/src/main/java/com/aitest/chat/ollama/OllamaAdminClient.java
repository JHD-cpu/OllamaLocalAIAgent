package com.aitest.chat.ollama;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * 查询 Ollama 是否在线、版本号和本地模型列表。
 * 连不上时返回 ok=false 和说明，不把异常抛到接口层。
 */
@Component
public class OllamaAdminClient {

	private final JsonMapper jsonMapper;

	private final RestClient restClient;

	public OllamaAdminClient(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		/* 探测应当很快失败，避免界面一直停在「正在检查」。 */
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(8));
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/** 请求 /api/version 和 /api/tags，汇总成一条连接状态。 */
	public OllamaInfo inspect(String baseUrl) {
		try {
			String version = text(readMap(baseUrl + "/api/version"), "version");
			List<OllamaModel> models = readModels(baseUrl);
			return new OllamaInfo(true, baseUrl, version, "已连接", models);
		}
		catch (RuntimeException ex) {
			return new OllamaInfo(false, baseUrl, "", friendly(ex), List.of());
		}
	}

	/** 读取已安装模型，并按名称不区分大小写排序，方便界面展示。 */
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

	/** 从 /api/tags 的一条模型记录里取出名称、参数量和上下文长度。 */
	private OllamaModel toModel(Map<?, ?> model) {
		Map<?, ?> details = model.get("details") instanceof Map<?, ?> map ? map : Map.of();
		return new OllamaModel(text(model, "name"), text(details, "parameter_size"),
				text(details, "quantization_level"), text(details, "family"), longValue(model.get("size")),
				longValue(details.get("context_length")));
	}

	/** GET 一个返回 JSON 对象的地址。空响应当成空 Map。 */
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

	/** 连接被拒绝或超时时给出固定说明，其他错误截到 300 字。 */
	private static String friendly(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage();
			if (message.contains("Connection refused") || message.contains("connect timed out")
					|| message.contains("timed out")) {
				return "无法连接 Ollama。请确认服务已启动，并且地址填写正确。";
			}
			current = current.getCause();
		}
		String message = ex.getMessage() == null ? "连接失败" : ex.getMessage();
		return message.length() > 300 ? message.substring(0, 300) : message;
	}

}
