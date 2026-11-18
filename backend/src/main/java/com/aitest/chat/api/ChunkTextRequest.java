package com.aitest.chat.api;

/** 新增或更新分块时的正文载荷。 */
public record ChunkTextRequest(String text) {
}