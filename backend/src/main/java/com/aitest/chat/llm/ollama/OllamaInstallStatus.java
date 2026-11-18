package com.aitest.chat.llm.ollama;

/**
 * Ollama 安装/启动任务的进度快照。
 * phase：idle / downloading / installing / starting / ready / error。
 */
public record OllamaInstallStatus(String phase, String message, boolean busy, boolean installed, String exePath) {

	/** 空闲且未在执行任务。 */
	public static OllamaInstallStatus idle(boolean installed, String exePath) {
		return new OllamaInstallStatus("idle", installed ? "本机已安装 Ollama" : "本机未安装 Ollama", false, installed,
				exePath == null ? "" : exePath);
	}

}
