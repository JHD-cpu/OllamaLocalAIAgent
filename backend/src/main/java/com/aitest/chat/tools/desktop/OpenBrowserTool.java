package com.aitest.chat.tools.desktop;

import java.awt.Desktop;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.aitest.chat.desktop.EmbeddedBrowserQueue;
import com.aitest.chat.desktop.EmbeddedBrowserQueue.OpenRequest;

/**
 * 打开网页：桌面模式下交给 Electron 内嵌多开窗口；否则用系统默认浏览器。
 */
public class OpenBrowserTool {

	private static final Logger log = LoggerFactory.getLogger(OpenBrowserTool.class);

	private final EmbeddedBrowserQueue queue;

	/** 注入打开队列。 */
	public OpenBrowserTool(EmbeddedBrowserQueue queue) {
		this.queue = queue;
	}

	@Tool(name = "open_browser", description = "在应用内嵌浏览器中打开网址，支持同时打开多个窗口。用户要求打开网站、网页或浏览器时调用。不要用 start 命令打开网站。")
	public String openBrowser(@ToolParam(description = "http 或 https 网址，例如 https://www.baidu.com", required = true) String url) {
		try {
			if (this.queue.desktop()) {
				OpenRequest request = this.queue.enqueue(url);
				log.info("已排队内嵌浏览器 url={}", request.url());
				return "已在应用内打开浏览器窗口：" + request.url() + "（可同时打开多个）";
			}
			String normalized = EmbeddedBrowserQueue.normalizeHttpUrl(url);
			if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				return "当前环境无法打开浏览器，请手动访问：" + normalized;
			}
			Desktop.getDesktop().browse(URI.create(normalized));
			log.info("已用系统默认浏览器打开 url={}", normalized);
			return "已用系统默认浏览器打开：" + normalized;
		}
		catch (Exception ex) {
			log.warn("打开浏览器失败：{}", ex.getMessage());
			return "打开失败：" + (ex.getMessage() == null ? "未知错误" : ex.getMessage());
		}
	}

}
