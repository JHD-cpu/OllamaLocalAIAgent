package com.aitest.chat.conversation;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import com.aitest.chat.llm.ChatModelFactory;
import com.aitest.chat.llm.LlmProvider;
import com.aitest.chat.settings.AppSettings;

/**
 * 当估算 token 达到上下文窗口约 80% 时，把较早对话压成摘要，保留最近几轮原文。
 */
@Component
public class ConversationCompressor {

	private static final Logger log = LoggerFactory.getLogger(ConversationCompressor.class);

	/** 触发压缩的上下文占用比例。 */
	static final double THRESHOLD = 0.80;

	/** 压缩后至少保留的尾部消息条数（含当前用户问题）。 */
	private static final int KEEP_RECENT = 4;

	/** 送去摘要的原文上限（字符），避免摘要请求本身超窗。 */
	private static final int SUMMARY_SOURCE_CHARS = 12000;

	/** 摘要输出大致上限（token）。 */
	private static final int SUMMARY_MAX_TOKENS = 700;

	private final ChatModelFactory chatModelFactory;

	public ConversationCompressor(ChatModelFactory chatModelFactory) {
		this.chatModelFactory = chatModelFactory;
	}

	public record Result(List<Message> messages, boolean compressed, int beforeTokens, int afterTokens, String note) {
	}

	/** 若占用 ≥ 80% 则压缩；否则原样返回。 */
	public Result maybeCompress(List<Message> messages, AppSettings settings) {
		if (messages == null || messages.isEmpty()) {
			return new Result(messages, false, 0, 0, "");
		}
		int before = ContextBudget.estimateAll(messages);
		int limit = Math.max(512, settings.numCtx());
		int trigger = Math.max(400, (int) (limit * THRESHOLD));
		if (before < trigger) {
			return new Result(messages, false, before, before, "");
		}
		SystemMessage system = null;
		List<Message> turns = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof SystemMessage systemMessage && system == null) {
				system = systemMessage;
			}
			else {
				turns.add(message);
			}
		}
		if (turns.size() <= KEEP_RECENT + 1) {
			// 轮次太少：只做硬裁剪，不强行摘要
			List<Message> fitted = ContextBudget.fit(messages, settings);
			int after = ContextBudget.estimateAll(fitted);
			boolean changed = after < before;
			return new Result(fitted, changed, before, after,
					changed ? "上下文接近上限，已截断部分内容（" + before + "→" + after + " tokens）" : "");
		}
		int split = Math.max(0, turns.size() - KEEP_RECENT);
		List<Message> older = new ArrayList<>(turns.subList(0, split));
		List<Message> recent = new ArrayList<>(turns.subList(split, turns.size()));
		if (older.isEmpty()) {
			return new Result(messages, false, before, before, "");
		}
		String summary = summarize(older, settings);
		if (summary == null || summary.isBlank()) {
			summary = extractiveSummary(older);
		}
		List<Message> rebuilt = new ArrayList<>();
		if (system != null) {
			rebuilt.add(system);
		}
		rebuilt.add(new SystemMessage("【对话摘要 · 自动压缩】\n" + summary.trim()
				+ "\n（以上为较早对话的压缩摘要，请结合后续原文继续回答。）"));
		rebuilt.addAll(recent);
		rebuilt = ContextBudget.fit(rebuilt, settings);
		int after = ContextBudget.estimateAll(rebuilt);
		String note = "上下文已达约 " + Math.min(99, before * 100 / limit) + "%，已压缩历史（" + before + "→" + after
				+ " tokens）";
		log.info("上下文压缩 before={} after={} trigger={} keptRecent={}", before, after, trigger, recent.size());
		return new Result(rebuilt, true, before, after, note);
	}

	private String summarize(List<Message> older, AppSettings settings) {
		String transcript = toTranscript(older);
		if (transcript.length() > SUMMARY_SOURCE_CHARS) {
			transcript = transcript.substring(0, SUMMARY_SOURCE_CHARS) + "\n…";
		}
		String promptText = """
				请将下列多轮对话压缩成简洁中文摘要，保留：用户目标、已确认事实、关键约束、未决问题、重要文件/数字。
				不要续写回答，不要空话，控制在 400 字以内。

				对话记录：
				%s
				""".formatted(transcript);
		try {
			ChatModel model = this.chatModelFactory.get(settings);
			Prompt prompt = new Prompt(List.of(new UserMessage(promptText)), summaryOptions(settings));
			ChatResponse response = model.call(prompt);
			if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
				return null;
			}
			String text = response.getResult().getOutput().getText();
			return text == null ? null : text.trim();
		}
		catch (Exception ex) {
			log.warn("LLM 摘要失败，改用抽取式压缩: {}", ex.toString());
			return null;
		}
	}

	private static ChatOptions summaryOptions(AppSettings settings) {
		if (settings.provider() == LlmProvider.OLLAMA) {
			OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
				.model(settings.model())
				.temperature(0.2)
				.topP(0.9)
				.numCtx(Math.min(settings.numCtx(), 16384))
				.numPredict(SUMMARY_MAX_TOKENS);
			builder.disableThinking();
			return builder.build();
		}
		return OpenAiChatOptions.builder()
			.model(settings.model())
			.temperature(0.2)
			.topP(0.9)
			.maxTokens(SUMMARY_MAX_TOKENS)
			.build();
	}

	private static String toTranscript(List<Message> messages) {
		StringBuilder sb = new StringBuilder();
		for (Message message : messages) {
			if (message instanceof UserMessage user) {
				sb.append("用户：").append(trimOne(user.getText())).append('\n');
			}
			else if (message instanceof AssistantMessage assistant) {
				sb.append("助手：").append(trimOne(assistant.getText())).append('\n');
			}
		}
		return sb.toString().trim();
	}

	private static String extractiveSummary(List<Message> older) {
		StringBuilder sb = new StringBuilder();
		int budget = 1800;
		for (Message message : older) {
			String line;
			if (message instanceof UserMessage user) {
				line = "用户：" + trimOne(user.getText());
			}
			else if (message instanceof AssistantMessage assistant) {
				line = "助手：" + trimOne(assistant.getText());
			}
			else {
				continue;
			}
			if (line.length() > 280) {
				line = line.substring(0, 280) + "…";
			}
			if (sb.length() + line.length() + 1 > budget) {
				sb.append("…\n");
				break;
			}
			sb.append(line).append('\n');
		}
		return sb.toString().trim();
	}

	private static String trimOne(String text) {
		if (text == null) {
			return "";
		}
		String t = text.replace('\r', ' ').trim();
		return t.length() > 2000 ? t.substring(0, 2000) + "…" : t;
	}

}
