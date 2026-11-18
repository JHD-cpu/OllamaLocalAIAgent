package com.aitest.chat.api;

import java.util.List;

/** 流式对话请求：多轮消息（可带多模态附件 data URL）。 */
public record ChatRequest(List<ChatTurn> messages) {

	/** 单轮消息；images 为可选的 data URL（base64）列表，含图片与文档。 */
	public record ChatTurn(String role, String content, List<String> images) {
	}

}
