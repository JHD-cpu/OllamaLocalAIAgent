package com.aitest.chat.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** 用当前 Ollama 嵌入模型做语义切分。句子按小批送去嵌入，避免一次请求过大。 */
@Component
class EmbeddingSemanticChunker {

	private static final int BATCH = 16;

	private final SettingsEmbeddingModel embeddingModel;

	private final RagProperties properties;

	EmbeddingSemanticChunker(SettingsEmbeddingModel embeddingModel, RagProperties properties) {
		this.embeddingModel = embeddingModel;
		this.properties = properties;
	}

	List<String> split(String text) {
		return SemanticChunker.split(text, this::embed, this.properties.getBreakpointPercentile(),
				this.properties.getChunkMaxChars());
	}

	private List<float[]> embed(List<String> sentences) {
		List<float[]> vectors = new ArrayList<>();
		for (int start = 0; start < sentences.size(); start += BATCH) {
			int end = Math.min(start + BATCH, sentences.size());
			vectors.addAll(this.embeddingModel.embed(sentences.subList(start, end)));
		}
		return vectors;
	}

}
