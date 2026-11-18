package com.aitest.chat.tools.time;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

/** 向模型提供本机当前日期时间（含星期与时区）。 */
public class CurrentTimeTool {
    private static final Logger log = LoggerFactory.getLogger(CurrentTimeTool.class);
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] WEEKDAYS = new String[]{"\u661f\u671f\u4e00", "\u661f\u671f\u4e8c", "\u661f\u671f\u4e09", "\u661f\u671f\u56db", "\u661f\u671f\u4e94", "\u661f\u671f\u516d", "\u661f\u671f\u65e5"};

    /** Spring AI 工具入口：返回格式化的当前时间文案。 */
    @Tool(name="current_time", description="\u83b7\u53d6\u5f53\u524d\u65e5\u671f\u548c\u65f6\u95f4\uff0c\u5305\u542b\u65f6\u533a\u548c\u661f\u671f\u3002\u7528\u6237\u8be2\u95ee\u73b0\u5728\u51e0\u70b9\u3001\u4eca\u5929\u51e0\u53f7\uff0c\u6216\u95ee\u9898\u4f9d\u8d56\u5f53\u524d\u65f6\u95f4\u65f6\u4f7f\u7528\u3002")
    public String currentTime() {
        String text = CurrentTimeTool.describe(ZonedDateTime.now(ZoneId.systemDefault()));
        log.info("\u83b7\u53d6\u5f53\u524d\u65f6\u95f4 {}", (Object)text);
        return text;
    }

    /** 将时刻格式化为中文星期 + 时区，供工具与系统提示共用。 */
    public static String describe(ZonedDateTime time) {
        DayOfWeek day = time.getDayOfWeek();
        String weekday = WEEKDAYS[day.getValue() - 1];
        return time.format(FORMAT) + " " + weekday + "\uff08" + time.getZone().getId() + "\uff09";
    }
}
