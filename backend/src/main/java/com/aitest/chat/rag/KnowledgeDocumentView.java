package com.aitest.chat.rag;

import java.time.LocalDateTime;

/** 返回给知识库页面的一条文档。 */
public record KnowledgeDocumentView(String id, String filename, String mediaType, String status, String errorMessage,
		Integer charCount, Integer chunkCount, LocalDateTime createdAt) {
}
