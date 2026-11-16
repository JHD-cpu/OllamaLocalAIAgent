package com.aitest.chat.ollama;

import java.time.Duration;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

/**
 * 按 Ollama 地址缓存一个聊天客户端。地址没变就复用，变了才重建。
 * 同步调用走 RestClient，流式回答走 WebClient，两边读超时都是 5 分钟。
 */
@Component
public class OllamaClientFactory {

	private final Object lock = new Object();

	private volatile String baseUrl;

	private volatile OllamaChatModel model;

	/**
	 * 取指定地址的客户端。先在锁外看缓存，命中就直接返回；
	 * 未命中再加锁复查，避免并发时建出两份客户端。
	 */
	public OllamaChatModel get(String baseUrl) {
		OllamaChatModel cached = this.model;
		if (cached != null && baseUrl.equals(this.baseUrl)) {
			return cached;
		}
		synchronized (this.lock) {
			if (this.model != null && baseUrl.equals(this.baseUrl)) {
				return this.model;
			}
			SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
			requestFactory.setConnectTimeout(Duration.ofSeconds(10));
			requestFactory.setReadTimeout(Duration.ofMinutes(5));
			HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(5));
			OllamaApi api = OllamaApi.builder()
				.baseUrl(baseUrl)
				.restClientBuilder(RestClient.builder().requestFactory(requestFactory))
				.webClientBuilder(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)))
				.build();
			this.model = OllamaChatModel.builder().ollamaApi(api).build();
			this.baseUrl = baseUrl;
			return this.model;
		}
	}

}
