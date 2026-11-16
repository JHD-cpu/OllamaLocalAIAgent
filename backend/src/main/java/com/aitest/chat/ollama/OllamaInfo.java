package com.aitest.chat.ollama;

import java.util.List;

/**
 * 一次 Ollama 探测的结果。ok 为 false 时 version 为空，message 说明原因，models 为空列表。
 */
public record OllamaInfo(boolean ok, String baseUrl, String version, String message, List<OllamaModel> models) {
}
