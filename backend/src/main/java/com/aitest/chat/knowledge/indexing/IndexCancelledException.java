package com.aitest.chat.knowledge.indexing;

/** 文档在入库/分块过程中被删除时抛出，用于协作式中止。 */
public final class IndexCancelledException extends RuntimeException {

	private final String documentId;

	public IndexCancelledException(String documentId) {
		super("文档已删除，停止分块：" + documentId);
		this.documentId = documentId == null ? "" : documentId;
	}

	public String documentId() {
		return this.documentId;
	}

}
