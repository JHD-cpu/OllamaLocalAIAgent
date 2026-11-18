package com.aitest.chat.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * 验证联网工具里不依赖真实网络的两段逻辑：必应 HTML 解析，以及内网地址拦截。
 */
class WebAccessToolsTest {

	/** 普通结果要保留标题、链接和摘要；必应自己的跳转链接 bing.com/ck 要丢掉。 */
	@Test
	void extractsBingResults() {
		String html = """
				<h2><a href="https://example.com/a">第一篇</a></h2>
				<p class="b_lineclamp2">摘要一</p>
				<h2><a href="https://www.bing.com/ck/a?u=1">跳转</a></h2>
				<p class="b_lineclamp2">不要这个</p>
				<h2><a href="https://example.com/b">第二篇</a></h2>
				<p class="b_lineclamp2">摘要二</p>
				""";

		var hits = WebAccessTools.extractBing(html, 5);

		assertEquals(2, hits.size());
		assertEquals("第一篇", hits.get(0).title());
		assertEquals("https://example.com/a", hits.get(0).url());
		assertEquals("摘要一", hits.get(0).snippet());
		assertEquals("摘要二", hits.get(1).snippet());
	}

	/** 回环地址、局域网地址和非 http 协议都不能被 web_fetch 打开。 */
	@Test
	void rejectsPrivateAddresses() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> WebAccessTools.checkPublic(URI.create("http://127.0.0.1/secret")));
		assertTrue(ex.getMessage().contains("内网"));
		assertThrows(IllegalArgumentException.class,
				() -> WebAccessTools.checkPublic(URI.create("http://192.168.1.8/")));
		assertThrows(IllegalArgumentException.class,
				() -> WebAccessTools.checkPublic(URI.create("file:///C:/secret.txt")));
	}

}
