package com.aitest.chat.llm;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.aitest.chat.settings.AppSettings;

/** 按提供方组装采样参数与工具回调。 */
@Component
public class ChatOptionsFactory {

	/** 构建本轮 Prompt 使用的 ChatOptions。 */
	public ChatOptions create(AppSettings settings, boolean toolsAllowed, ToolCallback[] toolCallbacks) {
		if (settings.provider() == LlmProvider.OLLAMA) {
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
			if (toolsAllowed && toolCallbacks != null && toolCallbacks.length > 0) {
				builder.toolCallbacks(toolCallbacks);
			}
			return builder.build();
		}
		OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
			.model(settings.model())
			.temperature(settings.temperature())
			.topP(settings.topP());
		if (settings.numPredict() > 0) {
			builder.maxTokens(settings.numPredict());
		}
		if (toolsAllowed && toolCallbacks != null && toolCallbacks.length > 0) {
			builder.toolCallbacks(toolCallbacks);
		}
		return builder.build();
	}

}
