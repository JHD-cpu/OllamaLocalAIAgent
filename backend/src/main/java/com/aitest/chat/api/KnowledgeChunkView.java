package com.aitest.chat.api;

/** 知识库分块 API 视图（id、序号、字数、正文）。 */
public record KnowledgeChunkView(String id, int index, int charCount, String text) {
}