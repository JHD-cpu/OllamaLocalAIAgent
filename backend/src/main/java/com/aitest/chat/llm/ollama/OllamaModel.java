package com.aitest.chat.llm.ollama;

/** Ollama 已安装模型的简要元数据。 */
public record OllamaModel(String name, String parameterSize, String quantization, String family, Long size,
		Long contextLength) {
}
