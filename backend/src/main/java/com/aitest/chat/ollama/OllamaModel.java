package com.aitest.chat.ollama;

/**
 * 本地已安装的一个 Ollama 模型。
 * size 是文件字节数，contextLength 是模型声明的上下文长度，两者都可能缺失。
 */
public record OllamaModel(String name, String parameterSize, String quantization, String family, Long size,
		Long contextLength) {
}
