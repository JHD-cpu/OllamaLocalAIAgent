package com.aitest.chat.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aitest.chat.desktop.DesktopWatchdog;
import com.aitest.chat.desktop.EmbeddedBrowserQueue;
import com.aitest.chat.desktop.EmbeddedBrowserQueue.OpenRequest;
import com.aitest.chat.desktop.EmbeddedBrowserQueue.ReadRequest;
import com.aitest.chat.desktop.InstalledSoftwareCatalog;
import com.aitest.chat.desktop.InstalledSoftwareSnapshot;
import com.aitest.chat.desktop.KnowledgeGraphWindowQueue;

/** 桌面客户端心跳、软件快照与内嵌浏览器队列 API。 */
@RestController
@RequestMapping("/api/desktop")
public class DesktopController {

	private final DesktopWatchdog watchdog;

	private final InstalledSoftwareCatalog softwareCatalog;

	private final EmbeddedBrowserQueue browserQueue;

	private final KnowledgeGraphWindowQueue graphWindowQueue;

	public DesktopController(
		DesktopWatchdog watchdog,
		InstalledSoftwareCatalog softwareCatalog,
		EmbeddedBrowserQueue browserQueue,
		KnowledgeGraphWindowQueue graphWindowQueue
	) {
		this.watchdog = watchdog;
		this.softwareCatalog = softwareCatalog;
		this.browserQueue = browserQueue;
		this.graphWindowQueue = graphWindowQueue;
	}

	@GetMapping("/health")
	public Map<String, Object> health() {
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("desktop", this.watchdog.enabled());
		body.put("lastHeartbeat", this.watchdog.lastHeartbeatAt());
		return body;
	}

	@PostMapping("/heartbeat")
	public Map<String, Object> heartbeat() {
		this.watchdog.heartbeat();
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("at", this.watchdog.lastHeartbeatAt());
		return body;
	}

	@GetMapping("/software")
	public InstalledSoftwareSnapshot software() {
		return this.softwareCatalog.snapshot();
	}

	/** Electron 拉取待打开网址与待读页任务。 */
	@GetMapping("/browser/pending")
	public Map<String, Object> pendingBrowser() {
		List<OpenRequest> opens = this.browserQueue.drainOpen();
		List<ReadRequest> reads = this.browserQueue.drainRead();
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("desktop", this.browserQueue.desktop());
		body.put("items", opens);
		body.put("reads", reads);
		return body;
	}

	@PostMapping("/browser/open")
	public Map<String, Object> openBrowser(@RequestBody OpenBrowserRequest request) {
		OpenRequest queued = this.browserQueue.enqueue(request == null ? null : request.url());
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("url", queued.url());
		body.put("desktop", this.browserQueue.desktop());
		return body;
	}

	/** Electron 回写读页结果。 */
	@PostMapping("/browser/read-result")
	public Map<String, Object> readResult(@RequestBody ReadResultRequest request) {
		if (request != null && request.id() != null && !request.id().isBlank()) {
			this.browserQueue.completeRead(request.id(), request.text());
		}
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		return body;
	}

	/** 请求打开三维知识图谱桌面窗口。 */
	@PostMapping("/graph/open")
	public Map<String, Object> openGraph() {
		this.graphWindowQueue.requestOpen();
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("desktop", this.graphWindowQueue.desktop());
		return body;
	}

	/** Electron 拉取是否需要打开知识图谱窗口。 */
	@GetMapping("/graph/pending")
	public Map<String, Object> pendingGraph() {
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("ok", true);
		body.put("desktop", this.graphWindowQueue.desktop());
		body.put("open", this.graphWindowQueue.drainOpen());
		return body;
	}

	public record ReadResultRequest(String id, String text) {
	}

}
