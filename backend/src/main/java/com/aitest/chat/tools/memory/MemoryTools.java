package com.aitest.chat.tools.memory;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.aitest.chat.memory.MemoryService;

/** 跨会话长期记忆工具。 */
public class MemoryTools {

	private final MemoryService memoryService;

	public MemoryTools(MemoryService memoryService) {
		this.memoryService = memoryService;
	}

	@Tool(name = "memory_save", description = "把需要跨会话记住的用户偏好、项目约定或事实写入长期记忆。不要保存一次性的闲聊。")
	public String save(@ToolParam(description = "要记住的内容", required = true) String content,
			@ToolParam(description = "可选标签，逗号分隔", required = false) String tags) {
		return this.memoryService.save(content, tags);
	}

	@Tool(name = "memory_search", description = "检索长期记忆。回答依赖用户偏好或既往约定时调用。")
	public String search(@ToolParam(description = "检索关键词，可留空列出最近记忆", required = false) String query) {
		return this.memoryService.search(query);
	}

}
