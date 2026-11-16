package com.aitest.chat.ollama;

/** 设置面板在保存前探测某个 Ollama 地址时提交的请求体。 */
public record ProbeRequest(String baseUrl) {
}
