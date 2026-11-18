package com.aitest.chat.tools.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

import com.aitest.chat.desktop.EmbeddedBrowserQueue;

/** 读取应用内嵌浏览器当前页正文。 */
public class BrowserReadTool {

	private static final Logger log = LoggerFactory.getLogger(BrowserReadTool.class);

	private static final int MAX_CHARS = 12000;

	private final EmbeddedBrowserQueue queue;

	public BrowserReadTool(EmbeddedBrowserQueue queue) {
		this.queue = queue;
	}

	@Tool(name = "browser_read_page", description = "读取应用内嵌浏览器最近活动窗口的页面正文。页面已用 open_browser 打开后，需要总结或回答页内内容时调用。不要用它代替 web_fetch 抓取未打开的链接。")
	public String readPage() {
		if (!this.queue.desktop()) {
			return "当前不是桌面模式，无法读取内嵌浏览器。请改用 web_fetch。";
		}
		try {
			String text = this.queue.requestRead(15).trim();
			if (text.isEmpty()) {
				return "内嵌浏览器没有可读正文（可能尚未打开窗口或页面为空）。";
			}
			if (text.length() > MAX_CHARS) {
				text = text.substring(0, MAX_CHARS) + "\n…(已截断)";
			}
			log.info("已读取内嵌浏览器正文 chars={}", text.length());
			return "当前页正文：\n" + text;
		}
		catch (Exception ex) {
			log.warn("读取内嵌浏览器失败：{}", ex.getMessage());
			return "读取失败：" + (ex.getMessage() == null ? "未知错误" : ex.getMessage());
		}
	}

}
