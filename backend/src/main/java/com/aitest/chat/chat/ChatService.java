package com.aitest.chat.chat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.aitest.chat.ollama.OllamaClientFactory;
import com.aitest.chat.rag.KnowledgeHit;
import com.aitest.chat.rag.KnowledgeService;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;
import com.aitest.chat.web.WebAccessTools;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 对话服务：把前端发来的消息交给 Ollama，并在需要时联网搜索。
 * 结果以 SSE 事件流返回，事件类型有 thinking、content、tool、done、error。
 */
@Service
public class ChatService {

	private static final Logger log = LoggerFactory.getLogger(ChatService.class);

	/** 单次请求最多带多少轮历史，避免上下文无限变长。 */
	private static final int MAX_MESSAGES = 40;

	/** 单条消息的字符上限。 */
	private static final int MAX_CHARS = 20000;

	/** 从工具调用的 JSON 参数里取出 query 或 url。 */
	private static final Pattern TOOL_ARGUMENT = Pattern.compile("\"(query|url)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

	private final SettingsService settingsService;

	private final OllamaClientFactory clientFactory;

	private final KnowledgeService knowledgeService;

	/** 负责真正执行模型返回的工具调用。 */
	private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

	public ChatService(SettingsService settingsService, OllamaClientFactory clientFactory,
			KnowledgeService knowledgeService) {
		this.settingsService = settingsService;
		this.clientFactory = clientFactory;
		this.knowledgeService = knowledgeService;
	}

	/**
	 * 开始一轮对话。先校验并转成模型消息，再进入检索/回答循环。
	 * 任何失败都转成一条 error 事件，避免流直接中断。
	 */
	public Flux<ServerSentEvent<ChatEvent>> stream(ChatRequest request) {
		try {
			AppSettings settings = this.settingsService.get();
			List<Message> messages = toMessages(settings, request);
			log.info("开始对话 model={} think={} web={} rag={} messages={}", settings.model(), settings.think(),
					settings.webEnabled(), settings.ragEnabled(), messages.size());
			return Flux.defer(() -> round(withKnowledge(messages, settings), settings, 0))
				.subscribeOn(Schedulers.boundedElastic())
				.onErrorResume(ex -> Flux.just(event("error", friendly(ex))));
		}
		catch (RuntimeException ex) {
			return Flux.just(event("error", friendly(ex)));
		}
	}

	/**
	 * 一轮回答。未超过工具次数时把当前时间和联网工具交给模型，由它决定要不要调用。
	 * depth 是已经执行过的工具轮次。
	 */
	private Flux<ServerSentEvent<ChatEvent>> round(List<Message> messages, AppSettings settings, int depth) {
		if (depth >= settings.webMaxRounds()) {
			return streamAnswer(messages, settings);
		}
		return Flux.defer(() -> answerWithTools(messages, settings, depth)).subscribeOn(Schedulers.boundedElastic());
	}

	/**
	 * 流式请求模型，并附上当前时间和联网工具。模型若返回工具调用，就执行后再进入下一轮；
	 * 没有工具调用时，正文片段已经逐条推给前端。服务端不会自己发起搜索。
	 */
	private Flux<ServerSentEvent<ChatEvent>> answerWithTools(List<Message> messages, AppSettings settings, int depth) {
		Prompt prompt = new Prompt(messages, toOptions(settings, true));
		ChatResponse[] toolResponse = new ChatResponse[1];
		return this.clientFactory.get(settings.baseUrl())
			.stream(prompt)
			.concatMap(response -> {
				if (hasToolCalls(response)) {
					toolResponse[0] = response;
					return Flux.<ServerSentEvent<ChatEvent>>empty();
				}
				if (toolResponse[0] != null) {
					return Flux.<ServerSentEvent<ChatEvent>>empty();
				}
				return Flux.fromIterable(toEvents(response));
			})
			.concatWith(Flux.defer(() -> afterModel(prompt, toolResponse[0], settings, depth))
				.subscribeOn(Schedulers.boundedElastic()));
	}

	/** 模型没要工具就结束本轮；要了工具就执行，并把进度事件发给前端。 */
	private Flux<ServerSentEvent<ChatEvent>> afterModel(Prompt prompt, ChatResponse toolResponse, AppSettings settings,
			int depth) {
		if (toolResponse == null) {
			log.info("模型未调用工具");
			return Flux.just(event("done", ""));
		}
		log.info("执行工具 round={}", depth + 1);
		ToolExecutionResult result = this.toolCallingManager.executeToolCalls(prompt, toolResponse);
		return Flux.fromIterable(toolNotes(toolResponse))
			.concatWith(round(result.conversationHistory(), settings, depth + 1));
	}

	/** 关闭工具后流式生成最终回答，正文和思考过程都转成事件，最后补一条 done。 */
	private Flux<ServerSentEvent<ChatEvent>> streamAnswer(List<Message> messages, AppSettings settings) {
		Prompt prompt = new Prompt(messages, toOptions(settings, false));
		return this.clientFactory.get(settings.baseUrl())
			.stream(prompt)
			.concatMap(response -> Flux.fromIterable(toEvents(response)))
			.concatWithValues(event("done", ""));
	}

	/** 校验请求并转成 Spring AI 的消息列表。系统提示词加在最前面，最后一条必须是用户消息。 */
	private List<Message> toMessages(AppSettings settings, ChatRequest request) {
		if (request == null || request.messages() == null || request.messages().isEmpty()) {
			throw new IllegalArgumentException("请输入消息");
		}
		if (request.messages().size() > MAX_MESSAGES) {
			throw new IllegalArgumentException("对话太长了，请清空后再试");
		}
		List<Message> messages = new ArrayList<>();
		String systemPrompt = systemPrompt(settings);
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

	/**
	 * 知识库开启时，用最后一条用户问题检索，把命中片段追加成一条系统说明。
	 * 没有命中就保持原消息。联网搜索是否调用仍由后面的工具循环决定。
	 */
	private List<Message> withKnowledge(List<Message> messages, AppSettings settings) {
		if (!settings.ragEnabled()) {
			return messages;
		}
		String question = "";
		for (int i = messages.size() - 1; i >= 0; i--) {
			if (messages.get(i) instanceof UserMessage userMessage) {
				question = userMessage.getText();
				break;
			}
		}
		List<KnowledgeHit> hits = this.knowledgeService.retrieve(question, settings.ragTopK());
		if (hits.isEmpty()) {
			return messages;
		}
		StringBuilder note = new StringBuilder(
				"以下是知识库中检索到的资料。请优先依据这些资料回答，并在回答中注明来源文件名。资料没有覆盖的问题要说明不知道，不要编造来源。\n\n");
		for (int i = 0; i < hits.size(); i++) {
			KnowledgeHit hit = hits.get(i);
			note.append('[').append(i + 1).append("] 来源：").append(hit.filename()).append('\n');
			note.append(hit.text()).append("\n\n");
		}
		List<Message> prepared = new ArrayList<>(messages);
		int index = !prepared.isEmpty() && prepared.get(0) instanceof SystemMessage ? 1 : 0;
		prepared.add(index, new SystemMessage(note.toString().trim()));
		log.info("知识库命中 {} 条", hits.size());
		return prepared;
	}

	/** 系统提示词后面补上当前时间；联网开启时再补一句工具说明。是否调用仍由模型决定。 */
	private String systemPrompt(AppSettings settings) {
		String prompt = settings.systemPrompt() == null ? "" : settings.systemPrompt();
		String time = "当前时间：" + CurrentTimeTool.describe(ZonedDateTime.now(ZoneId.systemDefault()))
				+ "。回答日期、星期或时刻时必须使用这个时间，不要自行推算。需要重新确认时调用 current_time。";
		StringBuilder text = new StringBuilder();
		if (!prompt.isBlank()) {
			text.append(prompt).append("\n\n");
		}
		text.append(time);
		if (settings.webEnabled()) {
			String note = settings.webFetch()
					? "需要实时信息时可以调用 web_search，需要查看其中某个页面时可以调用 web_fetch。是否调用由你决定。"
					: "需要实时信息时可以调用 web_search。是否调用由你决定。";
			text.append("\n\n").append(note);
		}
		return text.toString();
	}

	/** 把界面上的采样参数写成 Ollama 选项。toolsAllowed 为真时注册当前时间和联网工具。 */
	private OllamaChatOptions toOptions(AppSettings settings, boolean toolsAllowed) {
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
			builder.toolCallbacks(toolCallbacks(settings));
		}
		return builder.build();
	}

	/** 当前时间工具始终提供。联网开启时再附上搜索，并按设置决定要不要打开网页。 */
	private ToolCallback[] toolCallbacks(AppSettings settings) {
		List<ToolCallback> callbacks = new ArrayList<>();
		callbacks.addAll(List.of(ToolCallbacks.from(new CurrentTimeTool())));
		if (settings.webEnabled()) {
			for (ToolCallback callback : ToolCallbacks.from(new WebAccessTools(settings))) {
				if (settings.webFetch() || !"web_fetch".equals(callback.getToolDefinition().name())) {
					callbacks.add(callback);
				}
			}
		}
		return callbacks.toArray(ToolCallback[]::new);
	}

	/** 模型这次回复里是否带了工具调用。 */
	private boolean hasToolCalls(ChatResponse response) {
		return response.getResult() != null && response.getResult().getOutput() != null
				&& response.getResult().getOutput().hasToolCalls();
	}

	/** 把工具调用翻译成前端能显示的短句，例如「搜索：天气」或「打开网页：https://…」。 */
	private List<ServerSentEvent<ChatEvent>> toolNotes(ChatResponse response) {
		List<ServerSentEvent<ChatEvent>> notes = new ArrayList<>();
		for (AssistantMessage.ToolCall call : response.getResult().getOutput().getToolCalls()) {
			String arguments = call.arguments() == null ? "" : call.arguments();
			if ("web_search".equals(call.name())) {
				notes.add(event("tool", "搜索：" + argument(arguments, "query")));
			}
			else if ("web_fetch".equals(call.name())) {
				notes.add(event("tool", "打开网页：" + argument(arguments, "url")));
			}
			else if ("current_time".equals(call.name())) {
				notes.add(event("tool", "获取当前时间"));
			}
			else {
				notes.add(event("tool", "调用工具：" + call.name()));
			}
		}
		return notes;
	}

	/** 用正则从工具参数 JSON 里取出指定字段，并还原转义后的引号和反斜杠。 */
	private String argument(String json, String name) {
		Matcher matcher = TOOL_ARGUMENT.matcher(json);
		while (matcher.find()) {
			if (name.equals(matcher.group(1))) {
				return matcher.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
			}
		}
		return "";
	}

	/**
	 * 把模型的一块输出拆成事件。思考过程可能在消息元数据或生成元数据里，两处都看。
	 * 空内容不发事件，避免前端出现空白气泡。
	 */
	private List<ServerSentEvent<ChatEvent>> toEvents(ChatResponse response) {
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

	/** 包一条 SSE 事件。type 决定前端怎么拼进消息列表。 */
	private ServerSentEvent<ChatEvent> event(String type, String text) {
		return ServerSentEvent.<ChatEvent>builder().data(new ChatEvent(type, text)).build();
	}

	/**
	 * 把连接失败、超时、模型不存在等异常收成用户能看懂的短句。
	 * 会沿着 cause 链查找，因为真正的原因经常包在外层异常里。
	 */
	private String friendly(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage();
			if (message.contains("向量库") || message.contains("嵌入模型")) {
				return abbreviate(message);
			}
			current = current.getCause();
		}
		current = ex;
		while (current != null) {
			String message = current.getMessage() == null ? "" : current.getMessage();
			if (message.contains("Connection refused") || message.contains("connect timed out")
					|| message.contains("timed out")) {
				return "无法连接 Ollama。请确认服务已启动，并且地址填写正确。";
			}
			current = current.getCause();
		}
		if (ex instanceof WebClientResponseException responseException) {
			String body = responseException.getResponseBodyAsString();
			if (body != null && body.contains("not found")) {
				return "找不到这个模型。请先在 Ollama 中拉取，或从列表里重新选择。";
			}
			if (body != null && !body.isBlank()) {
				return "模型调用失败：" + abbreviate(body);
			}
		}
		String message = ex.getMessage() == null ? "模型调用失败" : ex.getMessage();
		if (message.contains("not found")) {
			return "找不到这个模型。请先在 Ollama 中拉取，或从列表里重新选择。";
		}
		return "模型调用失败：" + abbreviate(message);
	}

	/** 错误原文可能很长，压成一行并截到 300 字再给前端。 */
	private String abbreviate(String value) {
		String trimmed = value.replace('\n', ' ').trim();
		return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
	}

}
