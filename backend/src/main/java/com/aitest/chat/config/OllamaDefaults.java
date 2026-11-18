package com.aitest.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.aitest.chat.settings.AppSettings;

/**
 * 绑定 application.yml 里的 app.ollama.*。默认值只写在那一处。
 */
@ConfigurationProperties(prefix = "app.ollama")
public class OllamaDefaults {

	private String llmProvider = "ollama";

	private String apiKey = "";

	private String baseUrl;

	private String model;

	private String systemPrompt;

	private double temperature;

	private double topP;

	private int topK;

	private int numCtx;

	private int numPredict;

	private double repeatPenalty;

	private boolean think;

	private boolean webEnabled;

	private String webProvider;

	private String webSearchUrl;

	private int webMaxResults;

	private boolean webFetch;

	private int webMaxRounds;

	private boolean ragEnabled;

	private String embeddingModel;

	private int ragTopK;

	private boolean codeEnabled;

	private String codeWorkspace;

	private int codeTimeoutSeconds;

	private int codeMaxRounds;

	public AppSettings toSettings() {
		return new AppSettings(llmProvider, apiKey, baseUrl, model, systemPrompt, temperature, topP, topK, numCtx,
				numPredict, repeatPenalty, think, webEnabled, webProvider, webSearchUrl, webMaxResults, webFetch,
				webMaxRounds, ragEnabled, embeddingModel, ragTopK, codeEnabled, codeWorkspace, codeTimeoutSeconds,
				codeMaxRounds);
	}

	public String getLlmProvider() {
		return llmProvider;
	}

	public void setLlmProvider(String llmProvider) {
		this.llmProvider = llmProvider;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	public double getTemperature() {
		return temperature;
	}

	public void setTemperature(double temperature) {
		this.temperature = temperature;
	}

	public double getTopP() {
		return topP;
	}

	public void setTopP(double topP) {
		this.topP = topP;
	}

	public int getTopK() {
		return topK;
	}

	public void setTopK(int topK) {
		this.topK = topK;
	}

	public int getNumCtx() {
		return numCtx;
	}

	public void setNumCtx(int numCtx) {
		this.numCtx = numCtx;
	}

	public int getNumPredict() {
		return numPredict;
	}

	public void setNumPredict(int numPredict) {
		this.numPredict = numPredict;
	}

	public double getRepeatPenalty() {
		return repeatPenalty;
	}

	public void setRepeatPenalty(double repeatPenalty) {
		this.repeatPenalty = repeatPenalty;
	}

	public boolean isThink() {
		return think;
	}

	public void setThink(boolean think) {
		this.think = think;
	}

	public boolean isWebEnabled() {
		return webEnabled;
	}

	public void setWebEnabled(boolean webEnabled) {
		this.webEnabled = webEnabled;
	}

	public String getWebProvider() {
		return webProvider;
	}

	public void setWebProvider(String webProvider) {
		this.webProvider = webProvider;
	}

	public String getWebSearchUrl() {
		return webSearchUrl;
	}

	public void setWebSearchUrl(String webSearchUrl) {
		this.webSearchUrl = webSearchUrl;
	}

	public int getWebMaxResults() {
		return webMaxResults;
	}

	public void setWebMaxResults(int webMaxResults) {
		this.webMaxResults = webMaxResults;
	}

	public boolean isWebFetch() {
		return webFetch;
	}

	public void setWebFetch(boolean webFetch) {
		this.webFetch = webFetch;
	}

	public int getWebMaxRounds() {
		return webMaxRounds;
	}

	public void setWebMaxRounds(int webMaxRounds) {
		this.webMaxRounds = webMaxRounds;
	}

	public boolean isRagEnabled() {
		return ragEnabled;
	}

	public void setRagEnabled(boolean ragEnabled) {
		this.ragEnabled = ragEnabled;
	}

	public String getEmbeddingModel() {
		return embeddingModel;
	}

	public void setEmbeddingModel(String embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

	public int getRagTopK() {
		return ragTopK;
	}

	public void setRagTopK(int ragTopK) {
		this.ragTopK = ragTopK;
	}

	public boolean isCodeEnabled() {
		return codeEnabled;
	}

	public void setCodeEnabled(boolean codeEnabled) {
		this.codeEnabled = codeEnabled;
	}

	public String getCodeWorkspace() {
		return codeWorkspace;
	}

	public void setCodeWorkspace(String codeWorkspace) {
		this.codeWorkspace = codeWorkspace;
	}

	public int getCodeTimeoutSeconds() {
		return codeTimeoutSeconds;
	}

	public void setCodeTimeoutSeconds(int codeTimeoutSeconds) {
		this.codeTimeoutSeconds = codeTimeoutSeconds;
	}

	public int getCodeMaxRounds() {
		return codeMaxRounds;
	}

	public void setCodeMaxRounds(int codeMaxRounds) {
		this.codeMaxRounds = codeMaxRounds;
	}

}
