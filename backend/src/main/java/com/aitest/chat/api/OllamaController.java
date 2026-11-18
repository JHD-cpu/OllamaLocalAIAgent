package com.aitest.chat.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aitest.chat.llm.ollama.OllamaAdminClient;
import com.aitest.chat.llm.ollama.OllamaInfo;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

/** Ollama 连通性与模型信息探测 API。 */
@RestController
@RequestMapping("/api")
public class OllamaController {

	private final SettingsService settingsService;

	private final OllamaAdminClient adminClient;

	/** 注入配置与 Ollama 管理客户端。 */
	public OllamaController(SettingsService settingsService, OllamaAdminClient adminClient) {
		this.settingsService = settingsService;
		this.adminClient = adminClient;
	}

	/** 使用当前已保存的 baseUrl 查询 Ollama 信息。 */
	@GetMapping("/ollama/info")
	public OllamaInfo info() {
		return this.adminClient.inspect(this.settingsService.get().baseUrl());
	}

	/** 使用请求中的 baseUrl（或归一化缺省）探测 Ollama。 */
	@PostMapping("/ollama/info")
	public OllamaInfo probe(@RequestBody ProbeRequest request) {
		String baseUrl = AppSettings.normalizeBaseUrl(request == null ? null : request.baseUrl());
		return this.adminClient.inspect(baseUrl);
	}

}