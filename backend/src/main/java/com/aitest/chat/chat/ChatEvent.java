package com.aitest.chat.chat;

/**
 * 流式对话里的一条 SSE 数据。
 * type 为 thinking（思考过程）、content（正文增量）、tool（正在搜索或打开网页）、
 * done（本轮结束）或 error（失败原因）。text 是对应的文字。
 */
public record ChatEvent(String type, String text) {
}
