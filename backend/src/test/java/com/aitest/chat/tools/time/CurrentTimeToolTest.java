package com.aitest.chat.tools.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

/** 确认时间文字带上日期、时刻、中文星期和时区。 */
class CurrentTimeToolTest {

	@Test
	void describesLocalDateTime() {
		ZonedDateTime time = ZonedDateTime.of(2026, 10, 19, 4, 28, 0, 0, ZoneId.of("Asia/Shanghai"));

		assertEquals("2026-10-19 04:28:00 星期一（Asia/Shanghai）", CurrentTimeTool.describe(time));
	}

	@Test
	void currentTimeUsesSystemZone() {
		String text = new CurrentTimeTool().currentTime();

		assertTrue(text.contains(ZoneId.systemDefault().getId()));
	}

}
