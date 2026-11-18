package com.aitest.chat.conversation;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import com.aitest.chat.api.ChatEvent;
import com.aitest.chat.api.ChatRequest;
import com.aitest.chat.llm.ollama.OllamaClientFactory;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;
import com.aitest.chat.tools.web.SearchedPages;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 对话编排：校验消息 → 工具轮次 → SSE 事件流。
 */
@Service
public class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

	private static final int MAX_MESSAGES = 40;

	private static final int MAX_CHARS = 20000;

	private final SettingsService settingsService;

	private final OllamaClientFactory clientFactory;

	private final SystemPromptBuilder systemPromptBuilder;

	private final ChatToolCallbacks toolCallbacks;

	private final ChatEventMapper events;

	private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

	/** 注入对话所需的服务与映射组件。 */
	public ChatService(SettingsService settingsService, OllamaClientFactory clientFactory,
			SystemPromptBuilder systemPromptBuilder, ChatToolCallbacks toolCallbacks, ChatEventMapper events) {
		this.settingsService = settingsService;
		this.clientFactory = clientFactory;
		this.systemPromptBuilder = systemPromptBuilder;
		this.toolCallbacks = toolCallbacks;
		this.events = events;
	}

	/** 校验请求后以 SSE 推送模型名、内容与工具轮次结果。 */
	public Flux<ServerSentEvent<ChatEvent>> stream(ChatRequest request) {
		try {
			AppSettings settings = this.settingsService.get();
			List<Message> messages = toMessages(settings, request);
			String question = lastUserText(messages);
			long started = System.nanoTime();
			log.info("开始对话 model={} think={} web={} rag={} code={} messages={} question=\"{}\"", settings.model(),
					settings.think(), settings.webEnabled(), settings.ragEnabled(), settings.codeEnabled(),
					messages.size(), ChatEventMapper.preview(question));
			SearchedPages pages = new SearchedPages();
			return Flux.defer(() -> Flux.concat(Flux.just(this.events.event("model", settings.model())),
					round(messages, settings, 0, pages)))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(ex -> {
					log.warn("对话失败 question=\"{}\"", ChatEventMapper.preview(question), ex);
					return Flux.just(this.events.event("error", OllamaErrorMessages.friendly(ex)));
				})
				.doFinally(signal -> log.info("对话结束 signal={} 耗时={}ms", signal,
						(System.nanoTime() - started) / 1_000_000));
		}
		catch (RuntimeException ex) {
			log.warn("对话请求无效", ex);
			return Flux.just(this.events.event("error", OllamaErrorMessages.friendly(ex)));
		}
	}

	/** 在未超轮次上限时走工具流，否则直接流式回答。 */
	private Flux<ServerSentEvent<ChatEvent>> round(List<Message> messages, AppSettings settings, int depth,
			SearchedPages pages) {
		int limit = this.toolCallbacks.toolRoundLimit(settings);
		if (depth >= limit) {
			log.info("已达工具轮次上限 {}，改为直接回答", limit);
			return streamAnswer(messages, settings);
		}
		return Flux.defer(() -> answerWithTools(messages, settings, depth, pages))
			.subscribeOn(Schedulers.boundedElastic());
	}

	/** 流式调用模型，有工具调用时延后执行并进入下一轮。 */
	private Flux<ServerSentEvent<ChatEvent>> answerWithTools(List<Message> messages, AppSettings settings, int depth,
			SearchedPages pages) {
		Prompt prompt = new Prompt(messages, this.toolCallbacks.toOptions(settings, true, pages));
		ChatResponse[] toolResponse = new ChatResponse[1];
		return this.clientFactory.get(settings.baseUrl())
			.stream(prompt)
			.concatMap(response -> {
				if (this.events.hasToolCalls(response)) {
					toolResponse[0] = response;
					return Flux.<ServerSentEvent<ChatEvent>>empty();
				}
				if (toolResponse[0] != null) {
					return Flux.<ServerSentEvent<ChatEvent>>empty();
				}
				return Flux.fromIterable(this.events.toEvents(response));
			})
			.concatWith(Flux.defer(() -> afterModel(prompt, toolResponse[0], settings, depth, pages))
				.subscribeOn(Schedulers.boundedElastic()));
	}

	/** 执行工具、推送来源与工具说明，并带上更新后的对话历史继续。 */
	private Flux<ServerSentEvent<ChatEvent>> afterModel(Prompt prompt, ChatResponse toolResponse, AppSettings settings,
			int depth, SearchedPages pages) {
		if (toolResponse == null) {
			log.info("模型未调用工具 round={}", depth + 1);
			return Flux.just(this.events.event("done", ""));
		}
		String names = toolResponse.getResult().getOutput().getToolCalls().stream()
			.map(call -> call.name() + "(" + ChatEventMapper.preview(call.arguments()) + ")")
			.reduce((left, right) -> left + ", " + right)
			.orElse("");
		log.info("执行工具 round={} calls={}", depth + 1, names);
		ToolExecutionResult result = this.toolCallingManager.executeToolCalls(prompt, toolResponse);
		return Flux.fromIterable(this.events.toolNotes(toolResponse))
			.concatWith(Flux.fromIterable(this.events.sourceEvents(pages)))
			.concatWith(round(result.conversationHistory(), settings, depth + 1, pages));
	}

	/** 关闭工具后纯流式生成最终回答。 */
	private Flux<ServerSentEvent<ChatEvent>> streamAnswer(List<Message> messages, AppSettings settings) {
		Prompt prompt = new Prompt(messages, this.toolCallbacks.toOptions(settings, false, null));
		return this.clientFactory.get(settings.baseUrl())
			.stream(prompt)
			.concatMap(response -> Flux.fromIterable(this.events.toEvents(response)))
			.concatWithValues(this.events.event("done", ""));
	}

	/** 将 API 消息转为 Spring AI 消息列表并附加系统提示。 */
	private List<Message> toMessages(AppSettings settings, ChatRequest request) {
		if (request == null || request.messages() == null || request.messages().isEmpty()) {
			throw new IllegalArgumentException("请输入消息");
		}
		if (request.messages().size() > MAX_MESSAGES) {
			throw new IllegalArgumentException("对话太长了，请清空后再试");
		}
		List<Message> messages = new ArrayList<>();
		String systemPrompt = this.systemPromptBuilder.build(settings);
		if (!systemPrompt.isBlank()) {
			messages.add(new SystemMessage(systemPrompt));
		}
		for (ChatRequest.ChatTurn turn : request.messages()) {
			if (turn == null || turn.content() == null || turn.content().isBlank()) {
				throw new IllegalArgumentException("消息内容不能为空");
			}
			if (turn.content().length() > MAX_CHARS) {
				throw new IllegalArgumentException("单条消息不能超过 " + MAX_CHARS + " 个字符");
			}
			String role = turn.role() == null ? "" : turn.role().trim();
			if ("user".equals(role)) {
				messages.add(new UserMessage(turn.content()));
			}
			else if ("assistant".equals(role)) {
				messages.add(new AssistantMessage(turn.content()));
			}
			else {
				throw new IllegalArgumentException("不支持的消息角色");
			}
		}
		Message last = messages.get(messages.size() - 1);
		if (!(last instanceof UserMessage)) {
			throw new IllegalArgumentException("最后一条消息需要是用户输入");
		}
		return messages;
	}

	/** 取最后一条用户消息文本，供日志与错误上下文使用。 */
	private static String lastUserText(List<Message> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof UserMessage userMessage) {
				return userMessage.getText();
			}
		}
		return "";
	}

}
