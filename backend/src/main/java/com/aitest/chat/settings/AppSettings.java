package com.aitest.chat.settings;

import java.net.URI;

import com.aitest.chat.llm.LlmProvider;

/**
 * 一份完整的应用配置：模型提供方（本机 Ollama 或远程 OpenAI 兼容）、采样参数、联网、知识库与代码工具。
 */
public record AppSettings(String llmProvider, String apiKey, String baseUrl, String model, String systemPrompt,
		double temperature, double topP, int topK, int numCtx, int numPredict, double repeatPenalty, boolean think,
		boolean webEnabled, String webProvider, String webSearchUrl, int webMaxResults, boolean webFetch,
		int webMaxRounds, boolean ragEnabled, String embeddingModel, int ragTopK, boolean codeEnabled,
		String codeWorkspace, int codeTimeoutSeconds, int codeMaxRounds) {

	public static final String WEB_PROMPT = "对于实时信息联网查询相关信息，不确定时列出所有可能的信息；";

	public LlmProvider provider() {
		return LlmProvider.from(this.llmProvider);
	}

	public static AppSettings normalize(AppSettings raw) {
		if (raw == null) {
			throw new IllegalArgumentException("配置不能为空");
		}
		LlmProvider provider = LlmProvider.from(raw.llmProvider());
		String apiKey = normalizeApiKey(raw.apiKey());
		if (provider.requiresApiKey() && apiKey.isEmpty()) {
			throw new IllegalArgumentException(provider.label() + " 需要填写 API Key");
		}
		if (apiKey.length() > 512) {
			throw new IllegalArgumentException("API Key 过长");
		}
		String baseUrl = normalizeBaseUrl(raw.baseUrl(), provider);
		String model = raw.model() == null ? "" : raw.model().trim();
		if (model.isEmpty() || model.length() > 200) {
			throw new IllegalArgumentException("模型名称不正确");
		}
		if (provider == LlmProvider.OLLAMA && containsWhitespace(model)) {
			throw new IllegalArgumentException("模型名称不正确");
		}
		String systemPrompt = applyWebPrompt(raw.systemPrompt(), raw.webEnabled());
		if (systemPrompt.length() > 4000) {
			throw new IllegalArgumentException("系统提示词不能超过 4000 个字符");
		}
		if (!Double.isFinite(raw.temperature()) || raw.temperature() < 0 || raw.temperature() > 2) {
			throw new IllegalArgumentException("温度需要在 0 到 2 之间");
		}
		if (!Double.isFinite(raw.topP()) || raw.topP() < 0 || raw.topP() > 1) {
			throw new IllegalArgumentException("Top P 需要在 0 到 1 之间");
		}
		if (raw.topK() < 1 || raw.topK() > 200) {
			throw new IllegalArgumentException("Top K 需要在 1 到 200 之间");
		}
		if (raw.numCtx() < 512 || raw.numCtx() > 131072) {
			throw new IllegalArgumentException("上下文长度需要在 512 到 131072 之间");
		}
		if (raw.numPredict() == 0 || raw.numPredict() < -2 || raw.numPredict() > 131072) {
			throw new IllegalArgumentException("最大生成长度需要是 -1、-2，或 1 到 131072");
		}
		if (!Double.isFinite(raw.repeatPenalty()) || raw.repeatPenalty() < 0 || raw.repeatPenalty() > 2) {
			throw new IllegalArgumentException("重复惩罚需要在 0 到 2 之间");
		}
		String webProvider = raw.webProvider() == null ? "" : raw.webProvider().trim().toLowerCase();
		if (!"bing".equals(webProvider) && !"searxng".equals(webProvider)) {
			throw new IllegalArgumentException("搜索来源只能是必应或 SearXNG");
		}
		String webSearchUrl = raw.webSearchUrl() == null ? "" : raw.webSearchUrl().trim();
		while (webSearchUrl.endsWith("/")) {
			webSearchUrl = webSearchUrl.substring(0, webSearchUrl.length() - 1);
		}
		if ("searxng".equals(webProvider)) {
			webSearchUrl = normalizeHttpBase(webSearchUrl, "SearXNG 地址");
		}
		else if (!webSearchUrl.isEmpty() && !webSearchUrl.startsWith("http://") && !webSearchUrl.startsWith("https://")) {
			throw new IllegalArgumentException("SearXNG 地址需要以 http:// 或 https:// 开头");
		}
		if (raw.webMaxResults() < 1) {
			throw new IllegalArgumentException("搜索结果条数至少为 1");
		}
		if (raw.webMaxRounds() < 1) {
			throw new IllegalArgumentException("检索次数至少为 1");
		}
		String embeddingModel = raw.embeddingModel() == null ? "" : raw.embeddingModel().trim();
		if (embeddingModel.isEmpty() || embeddingModel.length() > 128) {
			throw new IllegalArgumentException("嵌入模型名称不正确");
		}
		if (raw.ragTopK() < 1) {
			throw new IllegalArgumentException("知识库检索条数至少为 1");
		}
		// 旧配置可能是 40；硬顶 12，避免一次检索撑爆上下文
		int ragTopK = Math.min(12, raw.ragTopK());
		String codeWorkspace = raw.codeWorkspace() == null ? "" : raw.codeWorkspace().trim();
		if (codeWorkspace.isEmpty() || codeWorkspace.length() > 260) {
			throw new IllegalArgumentException("代码工作区路径不正确");
		}
		if (raw.codeTimeoutSeconds() < 1 || raw.codeTimeoutSeconds() > 120) {
			throw new IllegalArgumentException("命令超时需要在 1 到 120 秒之间");
		}
		if (raw.codeMaxRounds() < 1) {
			throw new IllegalArgumentException("代码生成轮次至少为 1");
		}
		return new AppSettings(provider.id(), apiKey, baseUrl, model, systemPrompt, raw.temperature(), raw.topP(),
				raw.topK(), raw.numCtx(), raw.numPredict(), raw.repeatPenalty(), raw.think(), raw.webEnabled(),
				webProvider, webSearchUrl, raw.webMaxResults(), raw.webFetch(), raw.webMaxRounds(), raw.ragEnabled(),
				embeddingModel, ragTopK, raw.codeEnabled(), codeWorkspace, raw.codeTimeoutSeconds(),
				raw.codeMaxRounds());
	}

	static String applyWebPrompt(String prompt, boolean webEnabled) {
		String text = prompt == null ? "" : prompt.replace(WEB_PROMPT, "").trim();
		if (!webEnabled) {
			return text;
		}
		return text.isEmpty() ? WEB_PROMPT : text + WEB_PROMPT;
	}

	public static String normalizeBaseUrl(String value) {
		return normalizeBaseUrl(value, LlmProvider.OLLAMA);
	}

	public static String normalizeBaseUrl(String value, LlmProvider provider) {
		String label = provider == LlmProvider.OLLAMA ? "Ollama 地址" : "接口地址";
		String baseUrl = value == null ? "" : value.trim();
		if (baseUrl.isEmpty()) {
			baseUrl = provider.defaultBaseUrl();
		}
		return normalizeHttpBase(baseUrl, label);
	}

	private static String normalizeHttpBase(String value, String label) {
		String baseUrl = value == null ? "" : value.trim();
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
			throw new IllegalArgumentException(label + "需要以 http:// 或 https:// 开头");
		}
		URI uri = URI.create(baseUrl);
		if (uri.getHost() == null) {
			throw new IllegalArgumentException(label + "不正确");
		}
		return baseUrl;
	}

	/** 去掉首尾空白、零宽字符，以及误粘贴的 Bearer 前缀。 */
	static String normalizeApiKey(String raw) {
		if (raw == null) {
			return "";
		}
		String key = raw.replace("\u200b", "").replace("\ufeff", "").trim();
		if (key.regionMatches(true, 0, "Bearer ", 0, 7)) {
			key = key.substring(7).trim();
		}
		return key;
	}

	private static boolean containsWhitespace(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (Character.isWhitespace(value.charAt(i))) {
				return true;
			}
		}
		return false;
	}

}
