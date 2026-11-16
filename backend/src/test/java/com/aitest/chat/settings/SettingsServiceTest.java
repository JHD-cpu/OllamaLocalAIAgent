package com.aitest.chat.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 旧配置文件缺字段时，用默认配置补上，已有字段保持不变。 */
class SettingsServiceTest {

	@Test
	void overlayKeepsStoredValuesAndFillsGaps() {
		Map<String, Object> defaults = new LinkedHashMap<>();
		defaults.put("model", "qwen3.5:9b");
		defaults.put("webEnabled", false);
		defaults.put("webMaxResults", 5);

		Map<String, Object> stored = new LinkedHashMap<>();
		stored.put("model", "gemma4:26b");
		stored.put("webEnabled", null);
		stored.put("extra", "忽略");

		Map<String, Object> merged = SettingsService.overlay(defaults, stored);

		assertEquals("gemma4:26b", merged.get("model"));
		assertEquals(false, merged.get("webEnabled"));
		assertEquals(5, merged.get("webMaxResults"));
		assertEquals(false, merged.containsKey("extra"));
	}

}
