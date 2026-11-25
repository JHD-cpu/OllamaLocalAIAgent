package com.aitest.chat.conversation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.aitest.chat.desktop.EmbeddedBrowserQueue;
import com.aitest.chat.desktop.InstalledSoftwareCatalog;
import com.aitest.chat.desktop.deps.ManagedDepsLayout;
import com.aitest.chat.knowledge.application.KnowledgeService;
import com.aitest.chat.llm.ChatOptionsFactory;
import com.aitest.chat.mcp.McpToolBridge;
import com.aitest.chat.memory.MemoryService;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.tools.code.CodeTools;
import com.aitest.chat.tools.desktop.BrowserReadTool;
import com.aitest.chat.tools.desktop.OpenAppTool;
import com.aitest.chat.tools.desktop.OpenBrowserTool;
import com.aitest.chat.knowledge.indexing.LlmPageOcr;
import com.aitest.chat.tools.desktop.ScreenshotTool;
import com.aitest.chat.tools.knowledge.KnowledgeSearchTool;
import com.aitest.chat.tools.memory.MemoryTools;
import com.aitest.chat.tools.time.CurrentTimeTool;
import com.aitest.chat.tools.web.SearchQueryRewriter;
import com.aitest.chat.tools.web.SearchedPages;
import com.aitest.chat.tools.web.WebAccessTools;

/** 按开关组装本轮可用的工具回调与聊天采样选项。 */
@Component
public class ChatToolCallbacks {

	private final KnowledgeService knowledgeService;

	private final SearchQueryRewriter queryRewriter;

	private final EmbeddedBrowserQueue browserQueue;

	private final InstalledSoftwareCatalog softwareCatalog;

	private final ManagedDepsLayout depsLayout;

	private final MemoryService memoryService;

	private final McpToolBridge mcpToolBridge;

	private final ChatOptionsFactory optionsFactory;

	private final LlmPageOcr visionOcr;

	public ChatToolCallbacks(KnowledgeService knowledgeService, SearchQueryRewriter queryRewriter,
			EmbeddedBrowserQueue browserQueue, InstalledSoftwareCatalog softwareCatalog, ManagedDepsLayout depsLayout,
			MemoryService memoryService, McpToolBridge mcpToolBridge, ChatOptionsFactory optionsFactory,
			LlmPageOcr visionOcr) {
		this.knowledgeService = knowledgeService;
		this.queryRewriter = queryRewriter;
		this.browserQueue = browserQueue;
		this.softwareCatalog = softwareCatalog;
		this.depsLayout = depsLayout;
		this.memoryService = memoryService;
		this.mcpToolBridge = mcpToolBridge;
		this.optionsFactory = optionsFactory;
		this.visionOcr = visionOcr;
	}

	public int toolRoundLimit(AppSettings settings) {
		if (!settings.codeEnabled()) {
			return settings.webMaxRounds();
		}
		return Math.max(settings.webMaxRounds(), settings.codeMaxRounds());
	}

	public ChatOptions toOptions(AppSettings settings, boolean toolsAllowed, SearchedPages pages) {
		ToolCallback[] callbacks = toolsAllowed ? toolCallbacks(settings, pages) : new ToolCallback[0];
		return this.optionsFactory.create(settings, toolsAllowed, callbacks);
	}

	public ToolCallback[] toolCallbacks(AppSettings settings, SearchedPages pages) {
		List<ToolCallback> callbacks = new ArrayList<>();
		callbacks.addAll(List.of(ToolCallbacks.from(new CurrentTimeTool())));
		callbacks.addAll(List.of(ToolCallbacks.from(new OpenBrowserTool(this.browserQueue))));
		callbacks.addAll(List.of(ToolCallbacks.from(new BrowserReadTool(this.browserQueue))));
		callbacks.addAll(List.of(ToolCallbacks.from(new OpenAppTool(this.softwareCatalog))));
		callbacks.addAll(List.of(ToolCallbacks.from(new ScreenshotTool(this.depsLayout, this.visionOcr))));
		callbacks.addAll(List.of(ToolCallbacks.from(new MemoryTools(this.memoryService))));
		callbacks.addAll(this.mcpToolBridge.callbacks());
		if (settings.ragEnabled()) {
			callbacks.addAll(List.of(ToolCallbacks.from(new KnowledgeSearchTool(this.knowledgeService, settings.ragTopK()))));
		}
		if (settings.webEnabled()) {
			for (ToolCallback callback : ToolCallbacks.from(new WebAccessTools(settings, pages, this.queryRewriter))) {
				if (settings.webFetch() || !"web_fetch".equals(callback.getToolDefinition().name())) {
					callbacks.add(callback);
				}
			}
		}
		if (settings.codeEnabled()) {
			callbacks.addAll(List.of(ToolCallbacks.from(new CodeTools(settings.codeWorkspace(), settings.codeTimeoutSeconds()))));
		}
		return callbacks.toArray(ToolCallback[]::new);
	}

}
