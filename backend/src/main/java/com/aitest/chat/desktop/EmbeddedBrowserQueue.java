package com.aitest.chat.desktop;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * 桌面壳内嵌浏览器队列：打开网址 + 读取当前页正文。
 * 后端工具入队，Electron 轮询取出并回写读页结果。
 */
@Component
public class EmbeddedBrowserQueue {

	private final ConcurrentLinkedQueue<OpenRequest> pendingOpen = new ConcurrentLinkedQueue<>();

	private final ConcurrentLinkedQueue<ReadRequest> pendingRead = new ConcurrentLinkedQueue<>();

	private final Map<String, CompletableFuture<String>> readWaiters = new ConcurrentHashMap<>();

	private final boolean desktop;

	public EmbeddedBrowserQueue() {
		this.desktop = Boolean.getBoolean("app.desktop");
	}

	public boolean desktop() {
		return this.desktop;
	}

	public OpenRequest enqueue(String rawUrl) {
		String url = normalizeHttpUrl(rawUrl);
		OpenRequest request = new OpenRequest(url, System.currentTimeMillis());
		this.pendingOpen.offer(request);
		return request;
	}

	/** 请求 Electron 读取最近活动内嵌窗口正文，最多等待 timeoutSeconds。 */
	public String requestRead(int timeoutSeconds) {
		if (!this.desktop) {
			throw new IllegalStateException("非桌面模式无法读取内嵌浏览器");
		}
		String id = UUID.randomUUID().toString();
		CompletableFuture<String> future = new CompletableFuture<>();
		this.readWaiters.put(id, future);
		this.pendingRead.offer(new ReadRequest(id, System.currentTimeMillis()));
		try {
			String text = future.get(Math.max(3, timeoutSeconds), TimeUnit.SECONDS);
			return text == null ? "" : text;
		}
		catch (Exception ex) {
			this.readWaiters.remove(id);
			throw new IllegalStateException("读取内嵌浏览器超时或失败：" + ex.getMessage(), ex);
		}
	}

	/** Electron 回写读页结果。 */
	public void completeRead(String id, String text) {
		CompletableFuture<String> future = this.readWaiters.remove(id);
		if (future != null) {
			future.complete(text == null ? "" : text);
		}
	}

	public List<OpenRequest> drainOpen() {
		List<OpenRequest> batch = new ArrayList<>();
		OpenRequest next;
		while ((next = this.pendingOpen.poll()) != null) {
			batch.add(next);
			if (batch.size() >= 20) {
				break;
			}
		}
		return batch;
	}

	public List<ReadRequest> drainRead() {
		List<ReadRequest> batch = new ArrayList<>();
		ReadRequest next;
		while ((next = this.pendingRead.poll()) != null) {
			batch.add(next);
			if (batch.size() >= 5) {
				break;
			}
		}
		return batch;
	}

	/** @deprecated 兼容旧调用，等同 {@link #drainOpen()} */
	public List<OpenRequest> drain() {
		return drainOpen();
	}

	public static String normalizeHttpUrl(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			throw new IllegalArgumentException("网址不能为空");
		}
		if (!value.contains("://")) {
			value = "https://" + value;
		}
		URI uri = URI.create(value);
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw new IllegalArgumentException("只支持 http 或 https 网址");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IllegalArgumentException("网址不正确");
		}
		return value;
	}

	public record OpenRequest(String url, long queuedAt) {
	}

	public record ReadRequest(String id, long queuedAt) {
	}

}
