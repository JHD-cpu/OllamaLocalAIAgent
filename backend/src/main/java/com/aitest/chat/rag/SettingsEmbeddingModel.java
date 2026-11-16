package com.aitest.chat.rag;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

/**
 * 用当前保存的 Ollama 地址和嵌入模型做向量。
 * 地址或模型名变了才重建客户端。维度固定为配置值，供 Qdrant 建集合。
 */
@Component
class SettingsEmbeddingModel implements EmbeddingModel {

	private final SettingsService settingsService;

	private final RagProperties properties;

	private final Object lock = new Object();

	private volatile String baseUrl;

	private volatile String model;

	private volatile OllamaEmbeddingModel delegate;

	SettingsEmbeddingModel(SettingsService settingsService, RagProperties properties) {
		this.settingsService = settingsService;
		this.properties = properties;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		return client().call(request);
	}

	@Override
	public float[] embed(org.springframework.ai.document.Document document) {
		EmbeddingResponse response = call(new EmbeddingRequest(List.of(document.getText()), null));
		return response.getResults().get(0).getOutput();
	}

	@Override
	public int dimensions() {
		return this.properties.getEmbeddingDimensions();
	}

	private OllamaEmbeddingModel client() {
		AppSettings settings = this.settingsService.get();
		OllamaEmbeddingModel cached = this.delegate;
		if (cached != null && settings.baseUrl().equals(this.baseUrl) && settings.embeddingModel().equals(this.model)) {
			return cached;
		}
		synchronized (this.lock) {
			if (this.delegate != null && settings.baseUrl().equals(this.baseUrl)
					&& settings.embeddingModel().equals(this.model)) {
				return this.delegate;
			}
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(Duration.ofSeconds(10));
			requestFactory.setReadTimeout(Duration.ofMinutes(5));
			OllamaApi api = OllamaApi.builder()
				.baseUrl(settings.baseUrl())
				.restClientBuilder(RestClient.builder().requestFactory(requestFactory))
				.build();
			this.delegate = OllamaEmbeddingModel.builder()
				.ollamaApi(api)
				.options(OllamaEmbeddingOptions.builder().model(settings.embeddingModel()).build())
				.build();
			this.baseUrl = settings.baseUrl();
			this.model = settings.embeddingModel();
			return this.delegate;
		}
	}

}
