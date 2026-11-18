package com.aitest.chat.knowledge.indexing;

/**
 * 允许安装完成后重置 OCR 环境探测缓存。
 * 由 {@link PaddlePageOcr} 实现，供依赖安装服务在装好 Python/PaddleOCR 后调用。
 */
public interface PaddleAvailability {

	/** 清除「不可用」缓存，下次 OCR 会重新探测。 */
	void resetAvailability();

	/**
	 * 探测当前环境。
	 * @return null 表示可用；否则为用户可读原因
	 */
	String probeReason();

}
