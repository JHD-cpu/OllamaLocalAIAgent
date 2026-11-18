package com.aitest.chat.llm.ollama;

import java.time.Duration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** 按 baseUrl 缓存 {@link OllamaChatModel}，避免重复建连。 */
@Component
public class OllamaClientFactory {
    private final Object lock = new Object();
    private volatile String baseUrl;
    private volatile OllamaChatModel model;

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    /** 返回与给定地址对应的聊天模型，地址变化时重建。 */
    public OllamaChatModel get(String baseUrl) {
        OllamaChatModel cached = this.model;
        if (cached != null && baseUrl.equals(this.baseUrl)) {
            return cached;
        }
        Object object = this.lock;
        synchronized (object) {
            if (this.model != null && baseUrl.equals(this.baseUrl)) {
                return this.model;
            }
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(10L));
            requestFactory.setReadTimeout(Duration.ofMinutes(5L));
            HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(5L));
            OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).restClientBuilder(RestClient.builder().requestFactory((ClientHttpRequestFactory)requestFactory)).webClientBuilder(WebClient.builder().clientConnector((ClientHttpConnector)new ReactorClientHttpConnector(httpClient))).build();
            this.model = OllamaChatModel.builder().ollamaApi(api).build();
            this.baseUrl = baseUrl;
            return this.model;
        }
    }
}
