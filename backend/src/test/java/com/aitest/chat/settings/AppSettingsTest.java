package com.aitest.chat.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证配置校验：地址和模型名会被整理，越界或缺少联网字段时会被拒绝。
 */
class AppSettingsTest {

	/** 地址末尾的斜杠和模型名两侧的空格在保存前会被去掉。 */
	@Test
	void trimsAddressAndModelName() {
		AppSettings settings = AppSettings.normalize(sample("http://127.0.0.1:11434/", " qwen3.5:9b ", 0.7));

		assertEquals("http://127.0.0.1:11434", settings.baseUrl());
		assertEquals("qwen3.5:9b", settings.model());
	}

	/** 温度只允许 0 到 2，超出范围时 normalize 抛异常。 */
	@Test
	void rejectsTemperatureOutOfRange() {
		assertThrows(IllegalArgumentException.class, () -> AppSettings.normalize(sample("http://127.0.0.1:11434", "qwen3.5:9b", 3)));
	}

	/** 搜索来源和条数必须由配置给出，缺省不再在校验里写死默认值。 */
	@Test
	void rejectsMissingWebFields() {
		assertThrows(IllegalArgumentException.class,
				() -> AppSettings.normalize(new AppSettings("http://127.0.0.1:11434", "qwen3.5:9b", "你好", 0.7, 0.9, 40,
						8192, -1, 1.1, false, false, null, null, 0, true, 0, false, "bge-m3", 4, false,
						"data/workspace", 20, 6)));
	}

	/** 嵌入模型名会去掉空白，检索条数至少为 1。 */
	@Test
	void rejectsRagTopKOutOfRange() {
		assertThrows(IllegalArgumentException.class, () -> AppSettings.normalize(sample("http://127.0.0.1:11434",
				"qwen3.5:9b", 0.7, 0)));
		AppSettings settings = AppSettings.normalize(new AppSettings("http://127.0.0.1:11434", "qwen3.5:9b", "你好", 0.7,
				0.9, 40, 8192, -1, 1.1, false, false, "bing", "http://127.0.0.1:8888", 5, true, 3, true, " bge-m3 ",
				4, false, "data/workspace", 20, 6));
		assertEquals("bge-m3", settings.embeddingModel());
		assertEquals(4, settings.ragTopK());
	}

	/** 造一份除地址、模型、温度以外都合法的配置。 */
	private AppSettings sample(String baseUrl, String model, double temperature) {
		return sample(baseUrl, model, temperature, 4);
	}

	private AppSettings sample(String baseUrl, String model, double temperature, int ragTopK) {
		return new AppSettings(baseUrl, model, "你好", temperature, 0.9, 40, 8192, -1, 1.1, false, false, "bing",
				"http://127.0.0.1:8888", 5, true, 3, false, "bge-m3", ragTopK, false, "data/workspace", 20, 6);
	}

}
