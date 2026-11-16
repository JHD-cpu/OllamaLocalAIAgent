package com.aitest.chat.chat;

import java.util.List;

/**
 * 前端发来的一轮对话。messages 按时间顺序排列，最后一条应是用户输入。
 * role 只接受 user 或 assistant。
 */
public record ChatRequest(List<ChatTurn> messages) {

	/** 一条历史消息。 */
	public record ChatTurn(String role, String content) {
	}

}
