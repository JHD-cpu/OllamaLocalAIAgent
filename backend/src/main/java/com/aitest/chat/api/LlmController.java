package com.aitest.chat.api;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.aitest.chat.llm.LlmProvider;
import com.aitest.chat.llm.ollama.OllamaAdminClient;
import com.aitest.chat.llm.ollama.OllamaInfo;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

import tools.jackson.databind.json.JsonMapper;

/** 当前 LLM 提供方状态与连通性探测（本机 Ollama / 远程 OpenAI 兼容）。 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

	private final SettingsService settingsService;

	private final OllamaAdminClient ollamaAdminClient;

	private final JsonMapper jsonMapper;

	public LlmController(SettingsService settingsService, OllamaAdminClient ollamaAdminClient, JsonMapper jsonMapper) {
		this.settingsService = settingsService;
		this.ollamaAdminClient = ollamaAdminClient;
		this.jsonMapper = jsonMapper;
	}

	@GetMapping("/info")
	public Map<String, Object> info() {
		return probeSettings(this.settingsService.get());
	}

	@PostMapping("/info")
	public Map<String, Object> probe(@RequestBody ProbeLlmRequest request) {
		AppSettings current = this.settingsService.get();
		LlmProvider provider = LlmProvider.from(request == null ? null : request.llmProvider());
		String apiKey = request != null && request.apiKey() != null ? request.apiKey() : current.apiKey();
		String baseUrl = request != null && request.baseUrl() != null && !request.baseUrl().isBlank()
				? request.baseUrl()
				: (provider.defaultBaseUrl().isBlank() ? current.baseUrl() : provider.defaultBaseUrl());
		String model = request != null && request.model() != null && !request.model().isBlank() ? request.model()
				: current.model();
		AppSettings trial = new AppSettings(provider.id(), apiKey, AppSettings.normalizeBaseUrl(baseUrl, provider),
				model, current.systemPrompt(), current.temperature(), current.topP(), current.topK(), current.numCtx(),
				current.numPredict(), current.repeatPenalty(), current.think(), current.webEnabled(),
				current.webProvider(), current.webSearchUrl(), current.webMaxResults(), current.webFetch(),
				current.webMaxRounds(), current.ragEnabled(), current.embeddingModel(), current.ragTopK(),
				current.codeEnabled(), current.codeWorkspace(), current.codeTimeoutSeconds(), current.codeMaxRounds());
		return probeSettings(AppSettings.normalize(trial));
	}

	@GetMapping("/providers")
	public List<Map<String, Object>> providers() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (LlmProvider provider : LlmProvider.values()) {
			LinkedHashMap<String, Object> item = new LinkedHashMap<>();
			item.put("id", provider.id());
			item.put("label", provider.label());
			item.put("defaultBaseUrl", provider.defaultBaseUrl());
			item.put("defaultModel", provider.defaultModel());
			item.put("remote", provider.remote());
			item.put("requiresApiKey", provider.requiresApiKey());
			list.add(item);
		}
		return list;
	}

	private Map<String, Object> probeSettings(AppSettings settings) {
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("provider", settings.provider().id());
		body.put("providerLabel", settings.provider().label());
		body.put("baseUrl", settings.baseUrl());
		body.put("model", settings.model());
		body.put("remote", settings.provider().remote());
		if (settings.provider() == LlmProvider.OLLAMA) {
			OllamaInfo info = this.ollamaAdminClient.inspect(settings.baseUrl());
			body.put("ok", info.ok());
			body.put("version", info.version());
			body.put("message", info.message());
			body.put("models", info.models());
			return body;
		}
		try {
			List<Map<String, Object>> models = listRemoteModels(settings);
			// 仅拉 /models 不能发现余额问题；再发一条极短对话探测
			probeRemoteChat(settings);
			body.put("ok", true);
			body.put("version", settings.provider().label());
			body.put("message", "已连接，对话可用");
			body.put("models", models);
		}
		catch (Exception ex) {
			body.put("ok", false);
			body.put("version", "");
			body.put("message", com.aitest.chat.conversation.OllamaErrorMessages.connectionFriendly(ex));
			body.put("models", List.of());
		}
		return body;
	}

	/** 用 chat/completions 做最小调用，以便发现 402 余额不足等问题。 */
	private void probeRemoteChat(AppSettings settings) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(30));
		String url = settings.baseUrl();
		if (!url.contains("/chat/completions")) {
			url = url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
		}
		String payload = """
				{"model":%s,"messages":[{"role":"user","content":"ping"}],"max_tokens":1,"stream":false}
				""".formatted(this.jsonMapper.writeValueAsString(settings.model()));
		RestClient.builder()
			.requestFactory(factory)
			.build()
			.post()
			.uri(URI.create(url))
			.header("Authorization", "Bearer " + settings.apiKey())
			.header("Content-Type", "application/json")
			.body(payload)
			.retrieve()
			.toBodilessEntity();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> listRemoteModels(AppSettings settings) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(5));
		factory.setReadTimeout(Duration.ofSeconds(15));
		String url = settings.baseUrl();
		if (!url.endsWith("/models") && !url.contains("/models?")) {
			url = url.endsWith("/") ? url + "models" : url + "/models";
		}
		try {
			String body = RestClient.builder()
				.requestFactory(factory)
				.build()
				.get()
				.uri(URI.create(url))
				.header("Authorization", "Bearer " + settings.apiKey())
				.retrieve()
				.body(String.class);
			List<Map<String, Object>> models = new ArrayList<>();
			if (body != null && !body.isBlank()) {
				Map<String, Object> map = this.jsonMapper.readValue(body, Map.class);
				Object data = map.get("data");
				if (data instanceof List<?> list) {
					for (Object item : list) {
						if (item instanceof Map<?, ?> row) {
							Object id = row.get("id");
							if (id != null) {
								LinkedHashMap<String, Object> one = new LinkedHashMap<>();
								one.put("name", String.valueOf(id));
								models.add(one);
							}
						}
					}
				}
			}
			if (!models.isEmpty()) {
				return models;
			}
		}
		catch (RuntimeException ignored) {
			/* 部分厂商无 /models；改由 chat 探测 */
		}
		LinkedHashMap<String, Object> current = new LinkedHashMap<>();
		current.put("name", settings.model());
		return List.of(current);
	}

	public record ProbeLlmRequest(String llmProvider, String apiKey, String baseUrl, String model) {
	}

}
