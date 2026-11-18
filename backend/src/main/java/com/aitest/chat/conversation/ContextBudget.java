package com.aitest.chat.conversation;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;

import com.aitest.chat.settings.AppSettings;

/**
 * 按上下文窗口粗估 token，裁掉过旧轮次并必要时截断最后一条用户消息。
 */
final class ContextBudget {

	private ContextBudget() {
	}

	/** 粗估 token：CJK≈1，其它≈4 字符 1 token，另加消息开销。 */
	static int estimateTokens(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int cjk = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN
					|| (c >= 0x3040 && c <= 0x30FF)
					|| (c >= 0xAC00 && c <= 0xD7AF)) {
				cjk++;
			}
		}
		int other = text.length() - cjk;
		return cjk + (other + 3) / 4;
	}

	static int estimateMessage(Message message) {
		int tokens = 6;
		if (message instanceof UserMessage user) {
			tokens += estimateTokens(user.getText());
			List<Media> media = user.getMedia();
			if (media != null) {
				// 图片占位：避免把视觉 token 估成 0
				tokens += media.size() * 256;
			}
		}
		else if (message instanceof AssistantMessage assistant) {
			tokens += estimateTokens(assistant.getText());
		}
		else if (message instanceof SystemMessage system) {
			tokens += estimateTokens(system.getText());
		}
		return tokens;
	}

	static int estimateAll(List<Message> messages) {
		int total = 0;
		for (Message message : messages) {
			total += estimateMessage(message);
		}
		return total;
	}

	/** 为生成预留的 token。 */
	static int replyReserve(AppSettings settings) {
		int ctx = Math.max(512, settings.numCtx());
		int predict = settings.numPredict();
		int reserve = predict > 0 ? predict : Math.min(1024, ctx / 4);
		reserve = Math.max(256, Math.min(reserve, ctx / 3));
		return reserve;
	}

	/** 提示侧可用 token 预算。 */
	static int promptBudget(AppSettings settings) {
		return Math.max(512, settings.numCtx() - replyReserve(settings));
	}

	/** 是否达到上下文占用压缩阈值（默认 80%）。 */
	static boolean reachesCompressionThreshold(List<Message> messages, AppSettings settings) {
		int limit = Math.max(512, settings.numCtx());
		return estimateAll(messages) >= (int) (limit * ConversationCompressor.THRESHOLD);
	}

	/** 单条附件文本建议上限（字符）。 */
	static int attachmentCharBudget(AppSettings settings) {
		int tokens = Math.max(800, promptBudget(settings) * 2 / 5);
		return Math.min(12000, tokens * 2);
	}

	/**
	 * 保留系统提示 + 尽量多的尾部对话；仍超限则截断最后一条用户正文。
	 */
	static List<Message> fit(List<Message> messages, AppSettings settings) {
		if (messages == null || messages.isEmpty()) {
			return messages;
		}
		int budget = promptBudget(settings);
		if (estimateAll(messages) <= budget) {
			return messages;
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
		if (turns.isEmpty()) {
			return messages;
		}
		List<Message> kept = new ArrayList<>();
		int used = system == null ? 0 : estimateMessage(system);
		for (int i = turns.size() - 1; i >= 0; i--) {
			Message turn = turns.get(i);
			int cost = estimateMessage(turn);
			if (!kept.isEmpty() && used + cost > budget) {
				continue;
			}
			kept.add(0, turn);
			used += cost;
			if (used > budget && kept.size() == 1) {
				break;
			}
		}
		// 确保至少保留最后一条用户消息
		Message last = turns.get(turns.size() - 1);
		if (kept.isEmpty() || kept.get(kept.size() - 1) != last) {
			kept.clear();
			kept.add(last);
			used = (system == null ? 0 : estimateMessage(system)) + estimateMessage(last);
		}
		List<Message> fitted = new ArrayList<>();
		if (system != null) {
			fitted.add(system);
		}
		fitted.addAll(kept);
		if (estimateAll(fitted) <= budget) {
			return fitted;
		}
		return truncateLastUser(fitted, budget);
	}

	private static List<Message> truncateLastUser(List<Message> messages, int budget) {
		int lastIndex = -1;
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof UserMessage) {
				lastIndex = i;
				break;
			}
		}
		if (lastIndex < 0) {
			return messages;
		}
		int others = 0;
		for (int i = 0; i < messages.size(); i++) {
			if (i != lastIndex) {
				others += estimateMessage(messages.get(i));
			}
		}
		int allow = Math.max(120, budget - others - 8);
		UserMessage user = (UserMessage) messages.get(lastIndex);
		String text = user.getText() == null ? "" : user.getText();
		int tokens = estimateTokens(text);
		if (tokens <= allow) {
			return messages;
		}
		// 按比例截断字符，偏保守多砍一点
		double ratio = (double) allow / (double) Math.max(1, tokens);
		int keepChars = Math.max(80, (int) (text.length() * ratio * 0.9));
		if (keepChars >= text.length()) {
			return messages;
		}
		String clipped = text.substring(0, keepChars) + "\n…（上下文已满，内容已截断；可在配置里增大「上下文长度」）";
		UserMessage replacement;
		List<Media> media = user.getMedia();
		if (media == null || media.isEmpty()) {
			replacement = new UserMessage(clipped);
		}
		else {
			replacement = UserMessage.builder().text(clipped).media(media).build();
		}
		List<Message> next = new ArrayList<>(messages);
		next.set(lastIndex, replacement);
		return next;
	}

}
