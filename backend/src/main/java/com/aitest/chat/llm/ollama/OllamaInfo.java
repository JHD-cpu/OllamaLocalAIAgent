package com.aitest.chat.llm.ollama;

import java.util.List;

/**
 * Ollama 探测结果：连通性、版本、模型列表，以及本机是否已安装、是否支持自动安装。
 */
public record OllamaInfo(boolean ok, String baseUrl, String version, String message, List<OllamaModel> models,
		boolean installed, String exePath, boolean installable) {

	/** 兼容旧调用：未探测安装信息时的便捷构造。 */
	public OllamaInfo(boolean ok, String baseUrl, String version, String message, List<OllamaModel> models) {
		this(ok, baseUrl, version, message, models, false, "", false);
	}

}
