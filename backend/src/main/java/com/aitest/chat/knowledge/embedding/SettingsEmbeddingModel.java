package com.aitest.chat.knowledge.embedding;

import com.aitest.chat.knowledge.config.RagProperties;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;
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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 按应用设置动态连接 Ollama 的嵌入模型。 */
@Component
public class SettingsEmbeddingModel
implements EmbeddingModel {
    private final SettingsService settingsService;
    private final RagProperties properties;
    private final Object lock = new Object();
    private volatile String baseUrl;
    private volatile String model;
    private volatile OllamaEmbeddingModel delegate;

    /** 注入设置服务与 RAG 配置。 */
    public SettingsEmbeddingModel(SettingsService settingsService, RagProperties properties) {
        this.settingsService = settingsService;
        this.properties = properties;
    }

    /** 批量请求嵌入向量。 */
    public EmbeddingResponse call(EmbeddingRequest request) {
        return this.client().call(request);
    }

    /** 对单条文档文本做嵌入。 */
    public float[] embed(Document document) {
        EmbeddingResponse response = this.call(new EmbeddingRequest(List.of(document.getText()), null));
        return ((Embedding)response.getResults().get(0)).getOutput();
    }

    /** 返回配置的向量维度。 */
    public int dimensions() {
        return this.properties.getEmbeddingDimensions();
    }

    /** 按当前 Ollama 地址与嵌入模型获取或重建客户端。 */
    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private OllamaEmbeddingModel client() {
        AppSettings settings = this.settingsService.get();
        OllamaEmbeddingModel cached = this.delegate;
        if (cached != null && settings.baseUrl().equals(this.baseUrl) && settings.embeddingModel().equals(this.model)) {
            return cached;
        }
        Object object = this.lock;
        synchronized (object) {
            if (this.delegate != null && settings.baseUrl().equals(this.baseUrl) && settings.embeddingModel().equals(this.model)) {
                return this.delegate;
            }
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(10L));
            requestFactory.setReadTimeout(Duration.ofMinutes(5L));
            OllamaApi api = OllamaApi.builder().baseUrl(settings.baseUrl()).restClientBuilder(RestClient.builder().requestFactory((ClientHttpRequestFactory)requestFactory)).build();
            this.delegate = OllamaEmbeddingModel.builder().ollamaApi(api).options(OllamaEmbeddingOptions.builder().model(settings.embeddingModel()).build()).build();
            this.baseUrl = settings.baseUrl();
            this.model = settings.embeddingModel();
            return this.delegate;
        }
    }
}
