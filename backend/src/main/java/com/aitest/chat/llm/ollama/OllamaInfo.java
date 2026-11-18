package com.aitest.chat.llm.ollama;

import java.util.List;

/** Ollama 探测结果：连通性、版本与模型列表。 */
public record OllamaInfo(boolean ok, String baseUrl, String version, String message, List<OllamaModel> models) {
}
