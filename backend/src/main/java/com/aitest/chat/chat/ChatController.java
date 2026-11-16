package com.aitest.chat.chat;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aitest.chat.ollama.OllamaAdminClient;
import com.aitest.chat.ollama.OllamaInfo;
import com.aitest.chat.ollama.ProbeRequest;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

import reactor.core.publisher.Flux;

/**
 * HTTP 入口：读写配置、探测 Ollama，以及以 SSE 流式返回对话。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

	private final SettingsService settingsService;

	private final OllamaAdminClient adminClient;

	private final ChatService chatService;

	public ChatController(SettingsService settingsService, OllamaAdminClient adminClient, ChatService chatService) {
		this.settingsService = settingsService;
		this.adminClient = adminClient;
		this.chatService = chatService;
	}

	/** 当前已保存的配置。 */
	@GetMapping("/settings")
	public AppSettings settings() {
		return this.settingsService.get();
	}

	/** 配置文件里的默认值，供界面上的「恢复默认」。 */
	@GetMapping("/settings/defaults")
	public AppSettings defaultSettings() {
		return this.settingsService.defaults();
	}

	/** 校验并保存配置，返回保存后的结果。 */
	@PutMapping("/settings")
	public AppSettings updateSettings(@RequestBody AppSettings settings) {
		return this.settingsService.update(settings);
	}

	/** 用已保存的地址检查 Ollama 是否在线，并带上模型列表。 */
	@GetMapping("/ollama/info")
	public OllamaInfo info() {
		return this.adminClient.inspect(this.settingsService.get().baseUrl());
	}

	/**
	 * 用请求里的地址做一次探测，不写入配置。
	 * 设置面板在保存前可以先确认这个地址通不通。
	 */
	@PostMapping("/ollama/info")
	public OllamaInfo probe(@RequestBody ProbeRequest request) {
		String baseUrl = AppSettings.normalizeBaseUrl(request == null ? null : request.baseUrl());
		return this.adminClient.inspect(baseUrl);
	}

	/** 流式对话。每个事件的 data 是 ChatEvent。 */
	@PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<ChatEvent>> stream(@RequestBody ChatRequest request) {
		return this.chatService.stream(request);
	}

}
