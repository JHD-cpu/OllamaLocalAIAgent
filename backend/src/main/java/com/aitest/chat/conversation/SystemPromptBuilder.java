package com.aitest.chat.conversation;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aitest.chat.desktop.InstalledSoftwareCatalog;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.tools.time.CurrentTimeTool;

/** 组装对话系统提示词（时间、知识库、联网、代码/本机软件说明）。 */
@Component
public class SystemPromptBuilder {

	private static final Logger log = LoggerFactory.getLogger(SystemPromptBuilder.class);

	private final InstalledSoftwareCatalog softwareCatalog;

	/** 注入本机软件目录，供代码模式提示词引用。 */
	public SystemPromptBuilder(InstalledSoftwareCatalog softwareCatalog) {
		this.softwareCatalog = softwareCatalog;
	}

	/** 拼接用户自定义与各功能模块的行为说明。 */
	public String build(AppSettings settings) {
		String prompt = settings.systemPrompt() == null ? "" : settings.systemPrompt();
		String time = "当前时间：" + CurrentTimeTool.describe(ZonedDateTime.now(ZoneId.systemDefault()))
				+ "。回答日期、星期或时刻时必须使用这个时间，不要自行推算。需要重新确认时调用 current_time。";
		StringBuilder text = new StringBuilder();
		if (!prompt.isBlank()) {
			text.append(prompt).append("\n\n");
		}
		text.append(time);
		log.info("系统提示词附加 {}", time);
		if (settings.ragEnabled()) {
			text.append("\n\n").append(
					"问题涉及已上传的文档、简历、制度或笔记时，调用 knowledge_search。新闻、天气、价格等实时信息不要调用它。没有返回相关资料时要说明不知道，不要编造来源。是否调用由你决定。");
		}
		if (settings.webEnabled()) {
			String note = settings.webFetch()
					? "只有需要实时信息、发生在你的知识截止之后、或你无法确定的事实时，才调用 web_search。已经知道的稳定知识直接回答，不要搜索。需要查看其中某个页面时可以调用 web_fetch。是否调用由你决定。"
					: "只有需要实时信息、发生在你的知识截止之后、或你无法确定的事实时，才调用 web_search。已经知道的稳定知识直接回答，不要搜索。是否调用由你决定。";
			text.append("\n\n").append(note);
		}
		text.append("\n\n").append(
				"主人要求打开网站或网页时，调用 open_browser，在应用内嵌浏览器中打开，可以同时打开多个窗口。不要用 start 或指定 chrome 来打开网站。已经打开的内嵌页面需要阅读或总结时，调用 browser_read_page。");
		text.append("\n\n").append(
				"打开本机程序时优先调用 open_app（按名称匹配）。需要看屏幕时调用 take_screenshot。跨会话要记住的用户偏好或事实，用 memory_save / memory_search。");
		if (settings.codeEnabled()) {
			text.append("\n\n").append(
					"你运行在主人的 Windows 电脑上，可以执行 cmd。打开其它本机程序时优先 open_app；复杂命令再用 run_cmd。不要说自己无法操作电脑。写代码、改文件时使用 create_file、write_file、read_file、run_file、run_cmd，文件路径相对于工作区。");
			String software = this.softwareCatalog.promptSummary();
			if (software != null && !software.isBlank()) {
				text.append("\n\n").append(software);
			}
		}
		return text.toString();
	}

}
