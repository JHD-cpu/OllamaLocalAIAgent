package com.aitest.chat.llm;

import java.time.Duration;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import com.aitest.chat.settings.AppSettings;

import reactor.netty.http.client.HttpClient;

/** 按当前配置缓存并创建 ChatModel（Ollama 或 OpenAI 兼容远程）。 */
@Component
public class ChatModelFactory {

	private final Object lock = new Object();

	private volatile String cacheKey;

	private volatile ChatModel model;

	public ChatModel get(AppSettings settings) {
		String key = cacheKeyOf(settings);
		ChatModel cached = this.model;
		if (cached != null && key.equals(this.cacheKey)) {
			return cached;
		}
		synchronized (this.lock) {
			if (this.model != null && key.equals(this.cacheKey)) {
				return this.model;
			}
			this.model = create(settings);
			this.cacheKey = key;
			return this.model;
		}
	}

	private static ChatModel create(AppSettings settings) {
		LlmProvider provider = settings.provider();
		if (provider == LlmProvider.OLLAMA) {
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(Duration.ofSeconds(10));
			requestFactory.setReadTimeout(Duration.ofMinutes(5));
			HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(5));
			OllamaApi api = OllamaApi.builder()
				.baseUrl(settings.baseUrl())
				.restClientBuilder(RestClient.builder().requestFactory(requestFactory))
				.webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)))
				.build();
			return OllamaChatModel.builder().ollamaApi(api).build();
		}
		OpenAiChatOptions defaults = OpenAiChatOptions.builder()
			.baseUrl(settings.baseUrl())
			.apiKey(settings.apiKey())
			.model(settings.model())
			.timeout(Duration.ofMinutes(5))
			.build();
		return OpenAiChatModel.builder().options(defaults).build();
	}

	private static String cacheKeyOf(AppSettings settings) {
		return settings.provider().id() + "|" + settings.baseUrl() + "|" + settings.apiKey() + "|" + settings.model();
	}

}
