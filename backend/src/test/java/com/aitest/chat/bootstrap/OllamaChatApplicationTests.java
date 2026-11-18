package com.aitest.chat.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:sqlite:file:memdb1?mode=memory&cache=shared",
		"spring.datasource.driver-class-name=org.sqlite.JDBC",
		"app.rag.text-dir=target/test-knowledge",
		"app.rag.vector-store-file=target/test-vectors.json",
		"app.settings-file=target/test-settings.json",
		"app.software-file=target/test-software.json"
})
class OllamaChatApplicationTests {

	@Test
	void contextLoads() {
	}

}
