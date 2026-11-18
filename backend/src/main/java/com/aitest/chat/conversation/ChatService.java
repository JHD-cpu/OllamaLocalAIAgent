package com.aitest.chat.conversation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import com.aitest.chat.api.ChatEvent;
import com.aitest.chat.api.ChatRequest;
import com.aitest.chat.knowledge.indexing.CoarseTextSplitter;
import com.aitest.chat.knowledge.indexing.DocumentTextReader;
import com.aitest.chat.llm.ChatModelFactory;
import com.aitest.chat.llm.LlmProvider;
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

	private static final int MAX_MEDIA = 8;

	private final SettingsService settingsService;

	private final ChatModelFactory clientFactory;

	private final SystemPromptBuilder systemPromptBuilder;

	private final ChatToolCallbacks toolCallbacks;

	private final ChatEventMapper events;

	private final DocumentTextReader documentTextReader;

	private final ConversationCompressor conversationCompressor;

	private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

	/** 注入对话所需的服务与映射组件。 */
	public ChatService(SettingsService settingsService, ChatModelFactory clientFactory,
			SystemPromptBuilder systemPromptBuilder, ChatToolCallbacks toolCallbacks, ChatEventMapper events,
			DocumentTextReader documentTextReader, ConversationCompressor conversationCompressor) {
		this.settingsService = settingsService;
		this.clientFactory = clientFactory;
		this.systemPromptBuilder = systemPromptBuilder;
		this.toolCallbacks = toolCallbacks;
		this.events = events;
		this.documentTextReader = documentTextReader;
		this.conversationCompressor = conversationCompressor;
	}

	/** 校验请求后以 SSE 推送模型名、内容与工具轮次结果。 */
	public Flux<ServerSentEvent<ChatEvent>> stream(ChatRequest request) {
		long started = System.nanoTime();
		return Flux.defer(() -> {
			AppSettings settings = this.settingsService.get();
			List<Message> messages = toMessages(settings, request);
			ConversationCompressor.Result compressed = this.conversationCompressor.maybeCompress(messages, settings);
			messages = compressed.messages();
			messages = ContextBudget.fit(messages, settings);
			settings = expandOllamaContextIfNeeded(settings, messages);
			String question = lastUserText(messages);
			log.info(
					"开始对话 provider={} model={} think={} web={} rag={} code={} messages={} estTokens={} numCtx={} compressed={} question=\"{}\"",
					settings.provider().id(), settings.model(), settings.think(), settings.webEnabled(),
					settings.ragEnabled(), settings.codeEnabled(), messages.size(), ContextBudget.estimateAll(messages),
					settings.numCtx(), compressed.compressed(), ChatEventMapper.preview(question));
			SearchedPages pages = new SearchedPages();
			String modelLabel = settings.provider().label() + " · " + settings.model();
			Flux<ServerSentEvent<ChatEvent>> head = Flux.just(this.events.event("model", modelLabel));
			if (compressed.compressed() && compressed.note() != null && !compressed.note().isBlank()) {
				head = head.concatWithValues(this.events.event("tool", compressed.note()));
			}
			return Flux.concat(head, round(messages, settings, 0, pages))
				.onErrorResume(ex -> {
					log.error("对话失败 question=\"{}\"", ChatEventMapper.preview(question), ex);
					return Flux.just(this.events.event("error", OllamaErrorMessages.friendly(ex)));
				});
		})
			.subscribeOn(Schedulers.boundedElastic())
			.onErrorResume(ex -> {
				log.error("对话请求无效", ex);
				return Flux.just(this.events.event("error", OllamaErrorMessages.friendly(ex)));
			})
			.doFinally(signal -> log.info("对话结束 signal={} 耗时={}ms", signal,
					(System.nanoTime() - started) / 1_000_000));
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
		return this.clientFactory.get(settings)
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
		return this.clientFactory.get(settings)
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
			if (turn == null) {
				continue;
			}
			String content = turn.content() == null ? "" : turn.content();
			List<String> images = turn.images() == null ? List.of() : turn.images();
			String role = turn.role() == null ? "" : turn.role().trim();
			// 历史里可能残留「停止/失败」产生的空助手气泡，跳过即可
			if ("assistant".equals(role) && content.isBlank()) {
				continue;
			}
			if (content.isBlank() && images.isEmpty()) {
				throw new IllegalArgumentException("消息内容不能为空");
			}
			if (content.length() > MAX_CHARS) {
				throw new IllegalArgumentException("单条消息不能超过 " + MAX_CHARS + " 个字符");
			}
			if ("user".equals(role)) {
				messages.add(toUserMessage(settings, content.isBlank() ? "请根据附件回答" : content, images));
			}
			else if ("assistant".equals(role)) {
				messages.add(new AssistantMessage(content));
			}
			else {
				throw new IllegalArgumentException("不支持的消息角色");
			}
		}
		if (messages.stream().noneMatch(UserMessage.class::isInstance)) {
			throw new IllegalArgumentException("请输入消息");
		}
		Message last = messages.get(messages.size() - 1);
		if (!(last instanceof UserMessage)) {
			throw new IllegalArgumentException("最后一条消息需要是用户输入");
		}
		return messages;
	}

	/** 本机 Ollama：若提示接近窗口，临时抬高本轮 numCtx（不改配置文件）。 */
	private static AppSettings expandOllamaContextIfNeeded(AppSettings settings, List<Message> messages) {
		if (settings.provider() != LlmProvider.OLLAMA) {
			return settings;
		}
		int need = ContextBudget.estimateAll(messages) + ContextBudget.replyReserve(settings) + 64;
		if (need <= settings.numCtx()) {
			return settings;
		}
		int expanded = Math.min(131072, Math.max(16384, nextPowerOfTwo(need)));
		if (expanded <= settings.numCtx()) {
			return settings;
		}
		log.info("本轮临时提高 Ollama 上下文 {} -> {}（估算 need≈{}）", settings.numCtx(), expanded, need);
		return new AppSettings(settings.llmProvider(), settings.apiKey(), settings.baseUrl(), settings.model(),
				settings.systemPrompt(), settings.temperature(), settings.topP(), settings.topK(), expanded,
				settings.numPredict(), settings.repeatPenalty(), settings.think(), settings.webEnabled(),
				settings.webProvider(), settings.webSearchUrl(), settings.webMaxResults(), settings.webFetch(),
				settings.webMaxRounds(), settings.ragEnabled(), settings.embeddingModel(), settings.ragTopK(),
				settings.codeEnabled(), settings.codeWorkspace(), settings.codeTimeoutSeconds(),
				settings.codeMaxRounds());
	}

	private static int nextPowerOfTwo(int value) {
		int v = Math.max(1, value);
		int n = 1;
		while (n < v && n < 131072) {
			n <<= 1;
		}
		return Math.min(n, 131072);
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

	/**
	 * 组装用户消息：图片走视觉多模态；文档提取文本并入正文。
	 * 多数远程 OpenAI 兼容接口只接受 image/audio，不能把 pdf/docx 当 media。
	 */
	private UserMessage toUserMessage(AppSettings settings, String content, List<String> attachments) {
		List<Media> media = new ArrayList<>();
		StringBuilder text = new StringBuilder(content == null ? "" : content);
		int docIndex = 0;
		int attachBudget = ContextBudget.attachmentCharBudget(settings);
		for (String raw : attachments) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			ParsedDataUrl parsed = parseDataUrl(raw.trim());
			if (parsed == null) {
				continue;
			}
			if (isImageMime(parsed.mime()) && media.size() < MAX_MEDIA) {
				String name = imageFileName(parsed.mime(), media.size() + 1);
				media.add(Media.builder().mimeType(parsed.mime()).data(parsed.bytes()).name(name).build());
				continue;
			}
			if (isImageMime(parsed.mime())) {
				continue;
			}
			docIndex += 1;
			String extracted = extractDocumentText(parsed, docIndex);
			if (extracted == null || extracted.isBlank()) {
				throw new IllegalArgumentException("无法读取文档附件（第 " + docIndex + " 个）。请改用 pdf/docx/xlsx/txt 等可提取文本的格式。");
			}
			// 先分片再装入上下文，不因文件过大拒绝上传
			String packed = packAttachmentChunks(extracted, Math.min(MAX_CHARS, attachBudget) - text.length() - 64);
			if (!text.isEmpty()) {
				text.append("\n\n");
			}
			text.append("---\n附件：").append(parsed.fileName()).append('\n').append(packed);
		}
		String body = text.toString().trim();
		if (body.isEmpty()) {
			body = media.isEmpty() ? "" : "请根据图片回答";
		}
		if (body.length() > MAX_CHARS) {
			body = body.substring(0, MAX_CHARS) + "\n…（已按分片装入上下文，超出部分请改上传知识库）";
		}
		if (media.isEmpty()) {
			return new UserMessage(body);
		}
		return UserMessage.builder().text(body).media(media).build();
	}

	private String extractDocumentText(ParsedDataUrl parsed, int index) {
		String fileName = parsed.fileName();
		if (fileName.isBlank()) {
			fileName = "attach-" + index + extensionForMime(parsed.mime());
		}
		MimeType mime = parsed.mime();
		if (mime != null && (mime.getType().equals("text") || "application/json".equals(mime.toString())
				|| "application/xml".equals(mime.toString()))) {
			String text = new String(parsed.bytes(), StandardCharsets.UTF_8);
			if (text.startsWith("\ufeff")) {
				text = text.substring(1);
			}
			return text.trim();
		}
		Path temp = null;
		try {
			temp = Files.createTempFile("chat-doc-", "-" + safeFileName(fileName));
			Files.write(temp, parsed.bytes());
			return this.documentTextReader.read(fileName, temp);
		}
		catch (Exception ex) {
			log.warn("文档提取失败 name={} mime={}: {}", fileName, mime, ex.toString());
			return null;
		}
		finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				}
				catch (Exception ignored) {
					/* ignore */
				}
			}
		}
	}

	/** 附件正文先分片再装入可用字符预算，不因原文过长直接失败。 */
	private static String packAttachmentChunks(String extracted, int room) {
		if (extracted == null || extracted.isBlank()) {
			return "";
		}
		if (room < 200) {
			return "（文档已提取，但当前上下文不足；请改上传知识库后检索，或增大「上下文长度」。）";
		}
		List<String> parts = CoarseTextSplitter.split(extracted, Math.min(2200, Math.max(800, room / 3)));
		StringBuilder packed = new StringBuilder();
		int used = 0;
		int included = 0;
		for (String part : parts) {
			int need = part.length() + (packed.isEmpty() ? 0 : 2);
			if (used + need > room) {
				break;
			}
			if (!packed.isEmpty()) {
				packed.append("\n\n");
			}
			packed.append(part);
			used += need;
			included += 1;
		}
		if (included == 0) {
			String head = extracted.substring(0, Math.min(extracted.length(), room - 40));
			return head + "\n…（已分片截取）";
		}
		if (included < parts.size()) {
			packed.append("\n…（共 ")
				.append(parts.size())
				.append(" 片，已装入 ")
				.append(included)
				.append(" 片；其余请改上传知识库）");
		}
		return packed.toString();
	}

	private record ParsedDataUrl(MimeType mime, byte[] bytes, String fileName) {
	}

	private static ParsedDataUrl parseDataUrl(String dataUrl) {
		int comma = dataUrl.indexOf(',');
		if (!dataUrl.startsWith("data:") || comma < 0) {
			return null;
		}
		String meta = dataUrl.substring(5, comma).toLowerCase(Locale.ROOT);
		String payload = dataUrl.substring(comma + 1);
		if (!meta.contains(";base64")) {
			return null;
		}
		MimeType mime = resolveMime(meta);
		try {
			byte[] bytes = Base64.getDecoder().decode(payload);
			if (bytes.length == 0) {
				return null;
			}
			return new ParsedDataUrl(mime, bytes, guessFileName(mime));
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static boolean isImageMime(MimeType mime) {
		return mime != null && "image".equalsIgnoreCase(mime.getType());
	}

	private static MimeType resolveMime(String meta) {
		String type = meta;
		int semi = meta.indexOf(';');
		if (semi >= 0) {
			type = meta.substring(0, semi).trim();
		}
		if (type.isEmpty() || "*/*".equals(type)) {
			return MimeTypeUtils.APPLICATION_OCTET_STREAM;
		}
		try {
			return MimeType.valueOf(type);
		}
		catch (RuntimeException ex) {
			return MimeTypeUtils.APPLICATION_OCTET_STREAM;
		}
	}

	private static String guessFileName(MimeType mime) {
		if (mime == null) {
			return "attachment.bin";
		}
		String subtype = mime.getSubtype() == null ? "bin" : mime.getSubtype().toLowerCase(Locale.ROOT);
		return switch (subtype) {
			case "png" -> "image.png";
			case "jpeg", "jpg" -> "image.jpg";
			case "gif" -> "image.gif";
			case "webp" -> "image.webp";
			case "pdf" -> "document.pdf";
			case "plain" -> "document.txt";
			case "markdown", "x-markdown" -> "document.md";
			case "json" -> "document.json";
			case "csv" -> "document.csv";
			case "vnd.openxmlformats-officedocument.wordprocessingml.document" -> "document.docx";
			case "msword" -> "document.doc";
			case "vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "document.xlsx";
			case "vnd.ms-excel" -> "document.xls";
			default -> "attachment." + subtype.replaceAll("[^a-z0-9.+-]", "");
		};
	}

	private static String imageFileName(MimeType mime, int index) {
		String base = guessFileName(mime);
		int dot = base.lastIndexOf('.');
		if (dot <= 0) {
			return "image-" + index + ".png";
		}
		return "image-" + index + base.substring(dot);
	}

	private static String extensionForMime(MimeType mime) {
		String name = guessFileName(mime);
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot) : ".bin";
	}

	private static String safeFileName(String filename) {
		String base = filename == null ? "file" : filename.replaceAll("[\\\\/:*?\"<>|]+", "_");
		return base.length() > 64 ? base.substring(base.length() - 64) : base;
	}

}
