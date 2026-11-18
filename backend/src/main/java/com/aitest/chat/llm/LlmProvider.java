package com.aitest.chat.llm;

import java.util.Locale;

/** 对话模型提供方：本机 Ollama 或 OpenAI 兼容远程接口。 */
public enum LlmProvider {

	OLLAMA("ollama", "本机 Ollama", "http://127.0.0.1:11434", "qwen3.5:9b", false),
	DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat", true),
	DOUBAO("doubao", "豆包 / 火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "", true),
	OPENAI("openai", "OpenAI 兼容", "https://api.openai.com/v1", "gpt-4o-mini", true);

	private final String id;

	private final String label;

	private final String defaultBaseUrl;

	private final String defaultModel;

	private final boolean remote;

	LlmProvider(String id, String label, String defaultBaseUrl, String defaultModel, boolean remote) {
		this.id = id;
		this.label = label;
		this.defaultBaseUrl = defaultBaseUrl;
		this.defaultModel = defaultModel;
		this.remote = remote;
	}

	public String id() {
		return this.id;
	}

	public String label() {
		return this.label;
	}

	public String defaultBaseUrl() {
		return this.defaultBaseUrl;
	}

	public String defaultModel() {
		return this.defaultModel;
	}

	public boolean remote() {
		return this.remote;
	}

	public boolean requiresApiKey() {
		return this.remote;
	}

	/** 解析配置字符串，未知时回退 Ollama。 */
	public static LlmProvider from(String raw) {
		String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		for (LlmProvider provider : values()) {
			if (provider.id.equals(value)) {
				return provider;
			}
		}
		return OLLAMA;
	}

}
