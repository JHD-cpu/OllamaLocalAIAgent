package com.aitest.chat.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** RAG 相关配置（{@code app.rag} 前缀）。 */
@ConfigurationProperties(prefix="app.rag")
public class RagProperties {
    private String textDir;
    private String paddlePython;
    private String paddleScript;
    private String vectorStoreFile;
    private int embeddingDimensions;
    private int chunkMaxChars;
    private int breakpointPercentile;
    private int ocrMinChars;

    /** 知识库文本文件根目录。 */
    public String getTextDir() {
        return this.textDir;
    }

    /** 设置知识库文本文件根目录。 */
    public void setTextDir(String textDir) {
        this.textDir = textDir;
    }

    /** PaddleOCR 使用的 Python 可执行命令。 */
    public String getPaddlePython() {
        return this.paddlePython;
    }

    /** 设置 PaddleOCR 的 Python 命令。 */
    public void setPaddlePython(String paddlePython) {
        this.paddlePython = paddlePython;
    }

    /** PaddleOCR 推理脚本路径。 */
    public String getPaddleScript() {
        return this.paddleScript;
    }

    /** 设置 PaddleOCR 脚本路径。 */
    public void setPaddleScript(String paddleScript) {
        this.paddleScript = paddleScript;
    }

    /** 向量库 JSON 持久化文件路径。 */
    public String getVectorStoreFile() {
        return this.vectorStoreFile;
    }

    /** 设置向量库持久化文件路径。 */
    public void setVectorStoreFile(String vectorStoreFile) {
        this.vectorStoreFile = vectorStoreFile;
    }

    /** 嵌入向量维度。 */
    public int getEmbeddingDimensions() {
        return this.embeddingDimensions;
    }

    /** 设置嵌入向量维度。 */
    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }

    /** 语义分块单块最大字符数。 */
    public int getChunkMaxChars() {
        return this.chunkMaxChars;
    }

    /** 设置单块最大字符数。 */
    public void setChunkMaxChars(int chunkMaxChars) {
        this.chunkMaxChars = chunkMaxChars;
    }

    /** 相邻句嵌入距离断点百分位（1–99）。 */
    public int getBreakpointPercentile() {
        return this.breakpointPercentile;
    }

    /** 设置语义断点百分位。 */
    public void setBreakpointPercentile(int breakpointPercentile) {
        this.breakpointPercentile = breakpointPercentile;
    }

    /** PDF 文字层少于此字数时对该页做 OCR。 */
    public int getOcrMinChars() {
        return this.ocrMinChars;
    }

    /** 设置触发 OCR 的最小文字层字数。 */
    public void setOcrMinChars(int ocrMinChars) {
        this.ocrMinChars = ocrMinChars;
    }
}
