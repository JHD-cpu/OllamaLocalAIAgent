package com.aitest.chat.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import com.aitest.chat.api.ChatEvent;
import com.aitest.chat.tools.web.SearchedPages;

/** 模型输出与工具调用 → SSE ChatEvent。 */
@Component
public class ChatEventMapper {

	private static final Logger log = LoggerFactory.getLogger(ChatEventMapper.class);

	private static final Pattern TOOL_ARGUMENT = Pattern
		.compile("\"(query|url|path|command|name)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

	/** 构造单条 SSE 数据事件。 */
	public ServerSentEvent<ChatEvent> event(String type, String text) {
		return ServerSentEvent.<ChatEvent>builder().data(new ChatEvent(type, text)).build();
	}

	/** 判断本轮模型输出是否包含待执行的工具调用。 */
	public boolean hasToolCalls(ChatResponse response) {
		return response.getResult() != null && response.getResult().getOutput() != null
				&& response.getResult().getOutput().hasToolCalls();
	}

	/** 把思考链与正文片段映射为 content/thinking 事件。 */
	public List<ServerSentEvent<ChatEvent>> toEvents(ChatResponse response) {
		if (response.getResult() == null || response.getResult().getOutput() == null) {
			return List.of();
		}
		var generation = response.getResult();
		var output = generation.getOutput();
		List<ServerSentEvent<ChatEvent>> events = new ArrayList<>();
		Object thinking = output.getMetadata().get("thinking");
		if (thinking == null && generation.getMetadata() != null) {
			thinking = generation.getMetadata().get("thinking");
		}
		if (thinking != null && !thinking.toString().isEmpty()) {
			events.add(event("thinking", thinking.toString()));
		}
		String text = output.getText();
		if (text != null && !text.isEmpty()) {
			events.add(event("content", text));
		}
		return events;
	}

	/** 为各工具调用生成面向前端的简短 tool 说明。 */
	public List<ServerSentEvent<ChatEvent>> toolNotes(ChatResponse response) {
		List<ServerSentEvent<ChatEvent>> notes = new ArrayList<>();
		for (AssistantMessage.ToolCall call : response.getResult().getOutput().getToolCalls()) {
			String arguments = call.arguments() == null ? "" : call.arguments();
			if ("web_search".equals(call.name())) {
				notes.add(event("tool", "搜索：" + argument(arguments, "query")));
			}
			else if ("web_fetch".equals(call.name())) {
				notes.add(event("tool", "打开网页：" + argument(arguments, "url")));
			}
			else if ("knowledge_search".equals(call.name())) {
				notes.add(event("tool", "检索知识库：" + argument(arguments, "query")));
			}
			else if ("current_time".equals(call.name())) {
				notes.add(event("tool", "获取当前时间"));
			}
			else if ("create_file".equals(call.name())) {
				notes.add(event("tool", "创建文件：" + argument(arguments, "path")));
			}
			else if ("write_file".equals(call.name())) {
				notes.add(event("tool", "写入文件：" + argument(arguments, "path")));
			}
			else if ("read_file".equals(call.name())) {
				notes.add(event("tool", "读取文件：" + argument(arguments, "path")));
			}
			else if ("run_file".equals(call.name())) {
				notes.add(event("tool", "运行文件：" + argument(arguments, "path")));
			}
			else if ("run_cmd".equals(call.name())) {
				notes.add(event("tool", "执行命令：" + argument(arguments, "command")));
			}
			else {
				notes.add(event("tool", "调用工具：" + call.name()));
			}
		}
		return notes;
	}

	/** 取出本轮新收录的网页并转为 source 事件。 */
	public List<ServerSentEvent<ChatEvent>> sourceEvents(SearchedPages pages) {
		List<ServerSentEvent<ChatEvent>> events = new ArrayList<>();
		for (SearchedPages.Page page : pages.drain()) {
			log.info("搜索到的网页 title=\"{}\" url={}", preview(page.title()), page.url());
			events.add(event("source", page.json()));
		}
		return events;
	}

	/** 从工具参数字符串里解析指定字段（简单正则）。 */
	private String argument(String json, String name) {
		Matcher matcher = TOOL_ARGUMENT.matcher(json);
		while (matcher.find()) {
			if (name.equals(matcher.group(1))) {
				return matcher.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
			}
		}
		return "";
	}

	/** 日志用单行摘要，过长截断。 */
	public static String preview(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String oneLine = text.replaceAll("\\s+", " ").trim();
		return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
	}

}
