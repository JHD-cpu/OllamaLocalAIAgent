package com.aitest.chat.chat;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 给模型用的时间工具。返回本机时区的日期、时刻和星期。
 */
public class CurrentTimeTool {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final String[] WEEKDAYS = { "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日" };

	/**
	 * 获取当前时间。没有参数，避免模型编一个时区进来。
	 */
	@Tool(name = "current_time", description = "获取当前日期和时间，包含时区和星期。用户询问现在几点、今天几号，或问题依赖当前时间时使用。")
	public String currentTime() {
		return describe(ZonedDateTime.now(ZoneId.systemDefault()));
	}

	/** 把一个时刻写成「2026-10-19 04:28:00 星期一（Asia/Shanghai）」。 */
	static String describe(ZonedDateTime time) {
		DayOfWeek day = time.getDayOfWeek();
		String weekday = WEEKDAYS[day.getValue() - 1];
		return time.format(FORMAT) + " " + weekday + "（" + time.getZone().getId() + "）";
	}

}
