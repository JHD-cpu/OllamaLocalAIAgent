package com.aitest.chat.api;

/** 检索命中片段（文件名与文本）。 */
public record KnowledgeHit(String filename, String text) {
}