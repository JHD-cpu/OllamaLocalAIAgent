package com.aitest.chat.api;

import java.util.List;

/** 流式对话请求：多轮消息列表。 */
public record ChatRequest(List<ChatTurn> messages) {

    /** 单轮消息（角色与内容）。 */
    public record ChatTurn(String role, String content) {
    }
}