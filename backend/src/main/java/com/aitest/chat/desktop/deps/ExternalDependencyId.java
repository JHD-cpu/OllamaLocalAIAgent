package com.aitest.chat.desktop.deps;

/** 可由应用检测并尝试自动安装的本机外部依赖。 */
public enum ExternalDependencyId {

	/** CPython 解释器，供 OCR 与代码工具使用。 */
	PYTHON,

	/** PaddleOCR Python 包，供扫描版 PDF 识别。 */
	PADDLE_OCR,

	/** Node.js，供运行工作区 .js 文件。 */
	NODE,

	/** Ollama 中的嵌入模型（默认 bge-m3）。 */
	EMBEDDING_MODEL

}
