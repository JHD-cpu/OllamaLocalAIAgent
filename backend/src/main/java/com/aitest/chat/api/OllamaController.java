package com.aitest.chat.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aitest.chat.llm.ollama.OllamaAdminClient;
import com.aitest.chat.llm.ollama.OllamaInfo;
import com.aitest.chat.llm.ollama.OllamaInstallStatus;
import com.aitest.chat.llm.ollama.OllamaLifecycleService;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Ollama 连通性、安装、启动与模型拉取/删除 API。 */
@RestController
@RequestMapping("/api")
public class OllamaController {

	private final SettingsService settingsService;

	private final OllamaLifecycleService lifecycleService;

	private final OllamaAdminClient adminClient;

	public OllamaController(SettingsService settingsService, OllamaLifecycleService lifecycleService,
			OllamaAdminClient adminClient) {
		this.settingsService = settingsService;
		this.lifecycleService = lifecycleService;
		this.adminClient = adminClient;
	}

	@GetMapping("/ollama/info")
	public OllamaInfo info() {
		return this.lifecycleService.inspectWithInstallHint(this.settingsService.get().baseUrl());
	}

	@PostMapping("/ollama/info")
	public OllamaInfo probe(@RequestBody ProbeRequest request) {
		String baseUrl = AppSettings.normalizeBaseUrl(request == null ? null : request.baseUrl());
		return this.lifecycleService.inspectWithInstallHint(baseUrl);
	}

	@GetMapping("/ollama/install/status")
	public OllamaInstallStatus installStatus() {
		return this.lifecycleService.installStatus();
	}

	@PostMapping("/ollama/install")
	public OllamaInstallStatus install() {
		return this.lifecycleService.startInstall();
	}

	@PostMapping("/ollama/start")
	public OllamaInfo start() {
		return this.lifecycleService.startService(this.settingsService.get().baseUrl());
	}

	/** 已禁用应用内拉取模型（避免自动下载）。 */
	@PostMapping(value = "/ollama/pull", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<Map<String, Object>>> pull(@RequestBody ModelNameRequest request) {
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("type", "error");
		err.put("text", "已取消应用内下载模型，请在本机使用 ollama pull。");
		return Flux.just(ServerSentEvent.<Map<String, Object>>builder(err).build());
	}

	@DeleteMapping("/ollama/models")
	public Map<String, Object> delete(@RequestBody ModelNameRequest request) {
		this.adminClient.delete(this.settingsService.get().baseUrl(), request == null ? null : request.name());
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		return body;
	}

	public record ModelNameRequest(String name) {
	}

}
