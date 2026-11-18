package com.aitest.chat.api;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aitest.chat.conversation.ChatService;

import reactor.core.publisher.Flux;

/** 对话 SSE 流式接口。 */
@RestController
@RequestMapping("/api")
public class ChatStreamController {

	private final ChatService chatService;

	/** 注入对话编排服务。 */
	public ChatStreamController(ChatService chatService) {
		this.chatService = chatService;
	}

	/** 接收消息并以 Server-Sent Events 返回模型输出。 */
	@PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<ChatEvent>> stream(@RequestBody ChatRequest request) {
		return this.chatService.stream(request);
	}

}