package com.aitest.chat.rag;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;

/**
 * Qdrant 向量库。第一次写入或检索时才连接，Qdrant 没启动不会挡住应用启动。
 */
@Component
class KnowledgeVectorStore {

	private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStore.class);

	private final RagProperties properties;

	private final SettingsEmbeddingModel embeddingModel;

	private final Object lock = new Object();

	private volatile QdrantVectorStore store;

	KnowledgeVectorStore(RagProperties properties, SettingsEmbeddingModel embeddingModel) {
		this.properties = properties;
		this.embeddingModel = embeddingModel;
	}

	void add(List<Document> documents) {
		if (documents.isEmpty()) {
			return;
		}
		try {
			store().add(documents);
		}
		catch (RuntimeException ex) {
			throw down(ex);
		}
	}

	List<Document> search(String question, int topK) {
		try {
			List<Document> found = store()
				.similaritySearch(SearchRequest.builder().query(question).topK(topK).build());
			return found == null ? List.of() : found;
		}
		catch (RuntimeException ex) {
			throw down(ex);
		}
	}

	/** 按文档 id 删掉它的全部向量。文档还没有向量时，这是一次空删除。 */
	void deleteDocument(String documentId) {
		try {
			store().delete(new FilterExpressionBuilder().eq("documentId", documentId).build());
		}
		catch (RuntimeException ex) {
			throw down(ex);
		}
	}

	private QdrantVectorStore store() {
		QdrantVectorStore cached = this.store;
		if (cached != null) {
			return cached;
		}
		synchronized (this.lock) {
			if (this.store != null) {
				return this.store;
			}
			try {
				QdrantGrpcClient grpc = QdrantGrpcClient
					.newBuilder(this.properties.getQdrantHost(), this.properties.getQdrantPort(), false)
					.build();
				QdrantVectorStore created = QdrantVectorStore.builder(new QdrantClient(grpc), this.embeddingModel)
					.collectionName(this.properties.getCollection())
					.initializeSchema(true)
					.build();
				created.afterPropertiesSet();
				this.store = created;
				return created;
			}
			catch (Exception ex) {
				throw down(ex);
			}
		}
	}

	/**
	 * 写入和检索都会经过这里。嵌入模型缺失、Qdrant 没启动会被收成不同的提示，避免一律说成连不上。
	 */
	private IllegalStateException down(Throwable ex) {
		String detail = detail(ex);
		if (detail.contains("向量库")) {
			return ex instanceof IllegalStateException state ? state : new IllegalStateException(detail, ex);
		}
		log.warn("向量库操作失败：{}", detail, ex);
		if (detail.contains("not found") && detail.contains("model")) {
			return new IllegalStateException("嵌入模型不可用。请先在 Ollama 中拉取设置里的嵌入模型，默认是 bge-m3。", ex);
		}
		if (detail.contains("Connection refused") || detail.contains("UNAVAILABLE") || detail.contains("connect timed out")
				|| detail.contains("Connection reset")) {
			return new IllegalStateException(
					"无法连接向量库 Qdrant（" + this.properties.getQdrantHost() + ":" + this.properties.getQdrantPort() + "）。请先启动 Qdrant。",
					ex);
		}
		String brief = detail.replace('\n', ' ').trim();
		if (brief.length() > 200) {
			brief = brief.substring(0, 200);
		}
		return new IllegalStateException(brief.isEmpty() ? "向量库操作失败。" : "向量库操作失败：" + brief, ex);
	}

	/** 把异常链和 HTTP 响应体拼在一起，方便判断是模型缺失还是连不上。 */
	private static String detail(Throwable ex) {
		StringBuilder text = new StringBuilder();
		Throwable current = ex;
		while (current != null) {
			if (current.getMessage() != null) {
				text.append(current.getMessage()).append('\n');
			}
			if (current instanceof WebClientResponseException response) {
				text.append(response.getResponseBodyAsString()).append('\n');
			}
			current = current.getCause();
		}
		return text.toString();
	}

}
