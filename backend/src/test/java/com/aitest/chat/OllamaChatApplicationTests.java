package com.aitest.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 确认 Spring 容器能启动：配置绑定、控制器和服务都能装配起来。
 * 不连接真实的 Ollama。
 */
@SpringBootTest
class OllamaChatApplicationTests {

	/** 容器刷新成功即可，方法体留空。 */
	@Test
	void contextLoads() {
	}

}
