package com.aitest.chat.knowledge.embedding;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aitest.chat.knowledge.config.RagProperties;
import com.aitest.chat.llm.LlmProvider;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

/** 按应用设置连接嵌入模型：本机 Ollama 或远程 OpenAI 兼容接口。 */
@Component
public class SettingsEmbeddingModel implements EmbeddingModel {

	private final SettingsService settingsService;

	private final RagProperties properties;

	private final Object lock = new Object();

	private volatile String cacheKey;

	private volatile EmbeddingModel delegate;

	public SettingsEmbeddingModel(SettingsService settingsService, RagProperties properties) {
		this.settingsService = settingsService;
		this.properties = properties;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		return client().call(request);
	}

	@Override
	public float[] embed(Document document) {
		EmbeddingResponse response = call(new EmbeddingRequest(List.of(document.getText()), null));
		return ((Embedding) response.getResults().get(0)).getOutput();
	}

	@Override
	public int dimensions() {
		return this.properties.getEmbeddingDimensions();
	}

	private EmbeddingModel client() {
		AppSettings settings = this.settingsService.get();
		String key = settings.provider().id() + "|" + settings.baseUrl() + "|" + settings.apiKey() + "|"
				+ settings.embeddingModel();
		EmbeddingModel cached = this.delegate;
		if (cached != null && key.equals(this.cacheKey)) {
			return cached;
		}
		synchronized (this.lock) {
			if (this.delegate != null && key.equals(this.cacheKey)) {
				return this.delegate;
			}
			this.delegate = create(settings);
			this.cacheKey = key;
			return this.delegate;
		}
	}

	private static EmbeddingModel create(AppSettings settings) {
		if (settings.provider() == LlmProvider.OLLAMA) {
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(Duration.ofSeconds(10));
			requestFactory.setReadTimeout(Duration.ofMinutes(5));
			OllamaApi api = OllamaApi.builder()
				.baseUrl(settings.baseUrl())
				.restClientBuilder(RestClient.builder().requestFactory(requestFactory))
				.build();
			return OllamaEmbeddingModel.builder()
				.ollamaApi(api)
				.options(OllamaEmbeddingOptions.builder().model(settings.embeddingModel()).build())
				.build();
		}
		return OpenAiEmbeddingModel.builder()
			.options(OpenAiEmbeddingOptions.builder()
				.baseUrl(settings.baseUrl())
				.apiKey(settings.apiKey())
				.model(settings.embeddingModel())
				.build())
			.build();
	}

}
