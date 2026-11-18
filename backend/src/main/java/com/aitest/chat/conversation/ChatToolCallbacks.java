package com.aitest.chat.conversation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.aitest.chat.knowledge.application.KnowledgeService;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.tools.code.CodeTools;
import com.aitest.chat.tools.knowledge.KnowledgeSearchTool;
import com.aitest.chat.tools.time.CurrentTimeTool;
import com.aitest.chat.tools.web.SearchQueryRewriter;
import com.aitest.chat.tools.web.SearchedPages;
import com.aitest.chat.tools.web.WebAccessTools;

/** 按开关组装本轮可用的工具回调与 Ollama 采样选项。 */
@Component
public class ChatToolCallbacks {

	private final KnowledgeService knowledgeService;

	private final SearchQueryRewriter queryRewriter;

	/** 注入知识检索与搜索查询改写依赖。 */
	public ChatToolCallbacks(KnowledgeService knowledgeService, SearchQueryRewriter queryRewriter) {
		this.knowledgeService = knowledgeService;
		this.queryRewriter = queryRewriter;
	}

	/** 按联网与代码开关取较大的工具轮次上限。 */
	public int toolRoundLimit(AppSettings settings) {
		if (!settings.codeEnabled()) {
			return settings.webMaxRounds();
		}
		return Math.max(settings.webMaxRounds(), settings.codeMaxRounds());
	}

	/** 组装 Ollama 采样参数，并按需挂上工具回调。 */
	public OllamaChatOptions toOptions(AppSettings settings, boolean toolsAllowed, SearchedPages pages) {
		OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
			.model(settings.model())
			.temperature(settings.temperature())
			.topP(settings.topP())
			.topK(settings.topK())
			.numCtx(settings.numCtx())
			.numPredict(settings.numPredict())
			.repeatPenalty(settings.repeatPenalty());
		if (settings.think()) {
			builder.enableThinking();
		}
		else {
			builder.disableThinking();
		}
		if (toolsAllowed) {
			builder.toolCallbacks(toolCallbacks(settings, pages));
		}
		return builder.build();
	}

	/** 根据功能开关收集本轮可调用的 Spring AI 工具。 */
	public ToolCallback[] toolCallbacks(AppSettings settings, SearchedPages pages) {
		List<ToolCallback> callbacks = new ArrayList<>();
		callbacks.addAll(List.of(ToolCallbacks.from(new CurrentTimeTool())));
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
