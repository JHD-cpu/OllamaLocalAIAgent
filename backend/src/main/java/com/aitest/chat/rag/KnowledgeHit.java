package com.aitest.chat.rag;

/** 一次检索命中的资料，对话时会连同文件名写进提示词。 */
public record KnowledgeHit(String filename, String text) {
}
