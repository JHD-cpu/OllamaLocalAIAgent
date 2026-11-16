package com.aitest.chat.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库的服务器配置：文本目录、Qdrant 地址、语义切分和 PaddleOCR。
 * 对话开关、嵌入模型名称和检索条数在 {@link com.aitest.chat.settings.AppSettings} 里，用户可以改。
 */
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

	private String textDir;

	/** 调用 PaddleOCR 的 Python 命令。 */
	private String paddlePython;

	/** paddle_ocr.py 的路径，相对路径会从工作目录向上查找。 */
	private String paddleScript;

	private String qdrantHost;

	private int qdrantPort;

	private String collection;

	/** bge-m3 的向量维度。换嵌入模型后要删掉 Qdrant 集合再建，维度对不上无法写入。 */
	private int embeddingDimensions;

	private int maxUploadBytes;

	/** 单个向量块的字数上限。真正的断开点由相邻句子的语义距离决定。 */
	private int chunkMaxChars;

	/** 相邻句子距离里，高于这个百分位的位置才切开。越大越不容易切开。 */
	private int breakpointPercentile;

	/** 一页抽出的有效文字少于此值时，当作扫描页交给 PaddleOCR。 */
	private int ocrMinChars;

	public String getTextDir() {
		return textDir;
	}

	public void setTextDir(String textDir) {
		this.textDir = textDir;
	}

	public String getPaddlePython() {
		return paddlePython;
	}

	public void setPaddlePython(String paddlePython) {
		this.paddlePython = paddlePython;
	}

	public String getPaddleScript() {
		return paddleScript;
	}

	public void setPaddleScript(String paddleScript) {
		this.paddleScript = paddleScript;
	}

	public String getQdrantHost() {
		return qdrantHost;
	}

	public void setQdrantHost(String qdrantHost) {
		this.qdrantHost = qdrantHost;
	}

	public int getQdrantPort() {
		return qdrantPort;
	}

	public void setQdrantPort(int qdrantPort) {
		this.qdrantPort = qdrantPort;
	}

	public String getCollection() {
		return collection;
	}

	public void setCollection(String collection) {
		this.collection = collection;
	}

	public int getEmbeddingDimensions() {
		return embeddingDimensions;
	}

	public void setEmbeddingDimensions(int embeddingDimensions) {
		this.embeddingDimensions = embeddingDimensions;
	}

	public int getMaxUploadBytes() {
		return maxUploadBytes;
	}

	public void setMaxUploadBytes(int maxUploadBytes) {
		this.maxUploadBytes = maxUploadBytes;
	}

	public int getChunkMaxChars() {
		return chunkMaxChars;
	}

	public void setChunkMaxChars(int chunkMaxChars) {
		this.chunkMaxChars = chunkMaxChars;
	}

	public int getBreakpointPercentile() {
		return breakpointPercentile;
	}

	public void setBreakpointPercentile(int breakpointPercentile) {
		this.breakpointPercentile = breakpointPercentile;
	}

	public int getOcrMinChars() {
		return ocrMinChars;
	}

	public void setOcrMinChars(int ocrMinChars) {
		this.ocrMinChars = ocrMinChars;
	}

}
