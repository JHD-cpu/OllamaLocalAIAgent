package com.aitest.chat.api;

import java.time.LocalDateTime;

/** 知识库文档列表项（元数据、状态、进度、统计与创建时间）。 */
public record KnowledgeDocumentView(
	String id,
	String filename,
	String mediaType,
	String status,
	String errorMessage,
	Integer charCount,
	Integer chunkCount,
	Integer progress,
	String progressMessage,
	LocalDateTime createdAt
) {
}
