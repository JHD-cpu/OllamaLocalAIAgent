package com.aitest.chat.settings;

import java.net.URI;

/**
 * 一份完整的应用配置：Ollama 地址和采样参数、联网搜索，以及知识库检索。
 * 字段都经过 {@link #normalize(AppSettings)} 校验后才会保存或使用。
 * numPredict 为 -1 表示不限制生成长度，-2 表示尽量把上下文填满。
 */
public record AppSettings(String baseUrl, String model, String systemPrompt, double temperature, double topP, int topK,
		int numCtx, int numPredict, double repeatPenalty, boolean think, boolean webEnabled, String webProvider,
		String webSearchUrl, int webMaxResults, boolean webFetch, int webMaxRounds, boolean ragEnabled,
		String embeddingModel, int ragTopK) {

	/**
	 * 校验并整理一份配置。空白会被裁掉，尾部斜杠会被去掉。
	 * 缺字段时的默认值由 SettingsService 用 application.yml 补上，这里只拒绝不合法的值。
	 */
	public static AppSettings normalize(AppSettings raw) {
		if (raw == null) {
			throw new IllegalArgumentException("配置不能为空");
		}
		String baseUrl = normalizeBaseUrl(raw.baseUrl());
		String model = raw.model() == null ? "" : raw.model().trim();
		if (model.isEmpty() || model.length() > 128 || containsWhitespace(model)) {
			throw new IllegalArgumentException("模型名称不正确");
		}
		String systemPrompt = raw.systemPrompt() == null ? "" : raw.systemPrompt().trim();
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
		/* -1、-2 是 Ollama 的特殊值，0 没有意义，所以单独排除。 */
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
		/*
		 * SearXNG 地址必须是合法的 http(s) 基址。
		 * 用必应时这个地址可以留空；如果填了，也必须带协议，避免以后切换来源时才发现写错。
		 */
		if ("searxng".equals(webProvider)) {
			webSearchUrl = normalizeBaseUrl(webSearchUrl);
		}
		else if (!webSearchUrl.isEmpty() && !webSearchUrl.startsWith("http://") && !webSearchUrl.startsWith("https://")) {
			throw new IllegalArgumentException("SearXNG 地址需要以 http:// 或 https:// 开头");
		}
		if (raw.webMaxResults() < 1 || raw.webMaxResults() > 8) {
			throw new IllegalArgumentException("搜索结果条数需要在 1 到 8 之间");
		}
		if (raw.webMaxRounds() < 1 || raw.webMaxRounds() > 4) {
			throw new IllegalArgumentException("最多检索次数需要在 1 到 4 之间");
		}
		String embeddingModel = raw.embeddingModel() == null ? "" : raw.embeddingModel().trim();
		if (embeddingModel.isEmpty() || embeddingModel.length() > 128 || containsWhitespace(embeddingModel)) {
			throw new IllegalArgumentException("嵌入模型名称不正确");
		}
		if (raw.ragTopK() < 1 || raw.ragTopK() > 8) {
			throw new IllegalArgumentException("知识库检索条数需要在 1 到 8 之间");
		}
		return new AppSettings(baseUrl, model, systemPrompt, raw.temperature(), raw.topP(), raw.topK(), raw.numCtx(),
				raw.numPredict(), raw.repeatPenalty(), raw.think(), raw.webEnabled(), webProvider, webSearchUrl,
				raw.webMaxResults(), raw.webFetch(), raw.webMaxRounds(), raw.ragEnabled(), embeddingModel,
				raw.ragTopK());
	}

	/**
	 * 整理 Ollama 或 SearXNG 的基址：去掉尾部斜杠，要求 http(s)，并且必须有主机和端口。
	 * 端口为 0 说明地址里没写端口，这里不接受默认端口省略写法。
	 */
	public static String normalizeBaseUrl(String value) {
		String baseUrl = value == null ? "" : value.trim();
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
			throw new IllegalArgumentException("Ollama 地址需要以 http:// 或 https:// 开头");
		}
		URI uri = URI.create(baseUrl);
		if (uri.getHost() == null || uri.getPort() == 0) {
			throw new IllegalArgumentException("Ollama 地址不正确");
		}
		return baseUrl;
	}

	/** 模型名不允许空格，Ollama 的模型标识是一整段。 */
	private static boolean containsWhitespace(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (Character.isWhitespace(value.charAt(i))) {
				return true;
			}
		}
		return false;
	}

}
