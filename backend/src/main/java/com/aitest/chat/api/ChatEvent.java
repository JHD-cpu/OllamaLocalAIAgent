package com.aitest.chat.api;

/** SSE 对话事件（类型与文本载荷）。 */
public record ChatEvent(String type, String text) {
}