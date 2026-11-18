package com.aitest.chat.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AppSettingsTest {

	@Test
	void trimsAddressAndModelName() {
		AppSettings settings = AppSettings.normalize(sample("http://127.0.0.1:11434/", " qwen3.5:9b ", 0.7));
		assertEquals("http://127.0.0.1:11434", settings.baseUrl());
		assertEquals("qwen3.5:9b", settings.model());
		assertEquals("ollama", settings.llmProvider());
	}

	@Test
	void rejectsTemperatureOutOfRange() {
		assertThrows(IllegalArgumentException.class, () -> AppSettings.normalize(sample("http://127.0.0.1:11434", "qwen3.5:9b", 3)));
	}

	@Test
	void rejectsMissingWebFields() {
		assertThrows(IllegalArgumentException.class,
				() -> AppSettings.normalize(new AppSettings("ollama", "", "http://127.0.0.1:11434", "qwen3.5:9b", "你好",
						0.7, 0.9, 40, 8192, -1, 1.1, false, false, null, null, 0, true, 0, false, "bge-m3", 4, false,
						"data/workspace", 20, 6)));
	}

	@Test
	void requiresApiKeyForRemote() {
		assertThrows(IllegalArgumentException.class,
				() -> AppSettings.normalize(new AppSettings("deepseek", "", "https://api.deepseek.com", "deepseek-chat",
						"你好", 0.7, 0.9, 40, 8192, -1, 1.1, false, false, "bing", "http://127.0.0.1:8888", 5, true, 3,
						false, "bge-m3", 4, false, "data/workspace", 20, 6)));
	}

	@Test
	void acceptsDeepSeek() {
		AppSettings settings = AppSettings.normalize(new AppSettings("deepseek", "sk-test", "https://api.deepseek.com/",
				"deepseek-chat", "你好", 0.7, 0.9, 40, 8192, -1, 1.1, false, false, "bing", "http://127.0.0.1:8888", 5,
				true, 3, false, "bge-m3", 4, false, "data/workspace", 20, 6));
		assertEquals("deepseek", settings.llmProvider());
		assertEquals("https://api.deepseek.com", settings.baseUrl());
		assertEquals("sk-test", settings.apiKey());
	}

	@Test
	void rejectsRagTopKOutOfRange() {
		assertThrows(IllegalArgumentException.class,
				() -> AppSettings.normalize(sample("http://127.0.0.1:11434", "qwen3.5:9b", 0.7, 0)));
		AppSettings settings = AppSettings.normalize(new AppSettings("ollama", "", "http://127.0.0.1:11434", "qwen3.5:9b",
				"你好", 0.7, 0.9, 40, 8192, -1, 1.1, false, false, "bing", "http://127.0.0.1:8888", 5, true, 3, true,
				" bge-m3 ", 4, false, "data/workspace", 20, 6));
		assertEquals("bge-m3", settings.embeddingModel());
		assertEquals(4, settings.ragTopK());
	}

	private AppSettings sample(String baseUrl, String model, double temperature) {
		return sample(baseUrl, model, temperature, 4);
	}

	private AppSettings sample(String baseUrl, String model, double temperature, int ragTopK) {
		return new AppSettings("ollama", "", baseUrl, model, "你好", temperature, 0.9, 40, 8192, -1, 1.1, false, false,
				"bing", "http://127.0.0.1:8888", 5, true, 3, false, "bge-m3", ragTopK, false, "data/workspace", 20, 6);
	}

}
