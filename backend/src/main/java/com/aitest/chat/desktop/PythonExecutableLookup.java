package com.aitest.chat.desktop;

/**
 * 供知识库 OCR 查找本机 Python，避免 indexing 直接依赖软件扫描实现。
 */
public interface PythonExecutableLookup {

	/** 已解析的 python.exe 绝对路径；未知时返回 null 或空串。 */
	String pythonExe();

}
