package com.aitest.chat.knowledge.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.aitest.chat.settings.SettingsService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 让模型按「主题→子主题→概念」输出 JSON 树与标题，供后续分片。 */
@Component
public class DocumentChunkPlanner {

	private static final Logger log = LoggerFactory.getLogger(DocumentChunkPlanner.class);

	/** 粗分片后单窗通常已受控；仍保留上限以免异常超长段撑爆上下文。 */
	private static final int SAMPLE_CHARS = 16000;

	private static final int MAX_TOKENS = 2500;

	private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*}");

	private final ChatModelFactory chatModelFactory;

	private final SettingsService settingsService;

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	DocumentChunkPlanner(ChatModelFactory chatModelFactory, SettingsService settingsService) {
		this.chatModelFactory = chatModelFactory;
		this.settingsService = settingsService;
	}

	/** 生成主题树；失败时回退空树（调用方走语义切分）。 */
	public DocumentChunkPlan plan(String filename, String text, int defaultMaxChars) {
		AppSettings settings = this.settingsService.get();
		String sample = sample(text);
		String promptText = """
				你是知识结构化助手。请阅读下列文本文件，按「主题 → 子主题 → 概念」输出一棵知识树，用于 RAG 分片。
				只输出一个 JSON 对象，不要 Markdown，不要解释。

				JSON 结构：
				{
				  "docTitle": "文档标题",
				  "docType": "resume|manual|article|meeting_notes|code|other",
				  "maxChunkChars": 4000,
				  "tree": [
				    {
				      "title": "主题标题",
				      "level": "topic",
				      "anchor": "正文中可定位的原文短句或标题",
				      "children": [
				        {
				          "title": "子主题标题",
				          "level": "subtopic",
				          "anchor": "正文原文片段"
				        }
				      ]
				    }
				  ]
				}

				硬性规则：
				1. 默认只需「主题→子主题」两层；仅当一段子主题正文明显超过约 3500 字时，才拆成少量「概念」叶。
				2. 严禁过碎：不要把一句职责、一个技能词、一行时间做成独立叶节点。叶节点宜对应一整段可独立阅读的内容（约 800–3500 字）。
				3. 简历：主题=大章节（教育/工作/项目/技能）；子主题=一所学校、一段工作或一个项目（整段经历合为一叶），技能列表整段一叶。
				4. 手册/文章：主题=章，子主题=节；不要按要点/条目再拆概念。
				5. 叶节点（无 children）才是分片单位；同一经历/同一节必须完整落在同一叶。
				6. tree 按正文出现顺序；整篇叶节点总数尽量少（简历通常 6–15 个，长文按章节）；maxChunkChars 取 2500–6000，默认 4000。
				7. anchor 必须尽量使用正文里真实出现的原文，用于定位切分点；不要编造。

				文件名：%s
				正文样本：
				%s
				""".formatted(filename == null ? "" : filename, sample);
		try {
			ChatModel model = this.chatModelFactory.get(settings);
			Prompt prompt = new Prompt(List.of(new UserMessage(promptText)), options(settings));
			ChatResponse response = model.call(prompt);
			String raw = response == null || response.getResult() == null || response.getResult().getOutput() == null
				? null
				: response.getResult().getOutput().getText();
			DocumentChunkPlan plan = parse(raw, defaultMaxChars);
			plan = refineAnchors(plan, text);
			log.info("主题树就绪 docType={} title={} topics={} leaves={} maxChars={}",
				plan.docType(), plan.docTitle(), plan.tree().size(), plan.flattenLeaves().size(), plan.maxChunkChars());
			return plan;
		}
		catch (Exception ex) {
			log.warn("主题树生成失败，回退语义切分: {}", ex.toString());
			return DocumentChunkPlan.fallback(defaultMaxChars);
		}
	}

	private DocumentChunkPlan parse(String raw, int defaultMaxChars) throws Exception {
		if (raw == null || raw.isBlank()) {
			return DocumentChunkPlan.fallback(defaultMaxChars);
		}
		JsonNode root = this.objectMapper.readTree(extractJson(raw));
		int maxChars = root.path("maxChunkChars").asInt(defaultMaxChars);
		return new DocumentChunkPlan(
			root.path("docType").asString("general"),
			root.path("docTitle").asString(""),
			parseNodes(root.get("tree"), "topic"),
			maxChars <= 0 ? defaultMaxChars : maxChars
		);
	}

	private static List<ChunkTreeNode> parseNodes(JsonNode array, String defaultLevel) {
		if (array == null || !array.isArray()) {
			return List.of();
		}
		List<ChunkTreeNode> nodes = new ArrayList<>();
		for (JsonNode item : array) {
			if (item == null || item.isNull()) {
				continue;
			}
			String title = item.path("title").asString("").trim();
			if (title.isEmpty()) {
				continue;
			}
			String level = item.path("level").asString(defaultLevel).trim();
			String anchor = item.path("anchor").asString("").trim();
			String childDefault = switch (level.toLowerCase()) {
				case "topic", "主题" -> "subtopic";
				case "subtopic", "子主题" -> "concept";
				default -> "concept";
			};
			nodes.add(new ChunkTreeNode(title, anchor, level, parseNodes(item.get("children"), childDefault)));
		}
		return nodes;
	}

	/** 丢掉正文找不到的锚点节点；若叶锚点失效则尝试用 title。 */
	static DocumentChunkPlan refineAnchors(DocumentChunkPlan plan, String text) {
		if (!plan.hasTree() || text == null || text.isBlank()) {
			return plan;
		}
		List<ChunkTreeNode> refined = refineList(plan.tree(), text);
		return new DocumentChunkPlan(plan.docType(), plan.docTitle(), refined, plan.maxChunkChars());
	}

	private static List<ChunkTreeNode> refineList(List<ChunkTreeNode> nodes, String text) {
		List<ChunkTreeNode> out = new ArrayList<>();
		for (ChunkTreeNode node : nodes) {
			List<ChunkTreeNode> kids = refineList(node.children(), text);
			String anchor = node.anchor();
			if (anchor.isBlank() || !text.contains(anchor)) {
				anchor = text.contains(node.title()) ? node.title() : "";
			}
			if (kids.isEmpty() && anchor.isBlank()) {
				continue;
			}
			out.add(new ChunkTreeNode(node.title(), anchor, node.level(), kids));
		}
		return out;
	}

	private static String extractJson(String raw) {
		String trimmed = raw.trim();
		if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
			return trimmed;
		}
		Matcher matcher = JSON_OBJECT.matcher(trimmed);
		if (matcher.find()) {
			return matcher.group();
		}
		throw new IllegalStateException("模型未返回 JSON");
	}

	private static String sample(String text) {
		if (text == null) {
			return "";
		}
		String normalized = text.replace("\r\n", "\n").trim();
		if (normalized.length() <= SAMPLE_CHARS) {
			return normalized;
		}
		int head = SAMPLE_CHARS * 2 / 3;
		int tail = SAMPLE_CHARS - head;
		return normalized.substring(0, head) + "\n…\n" + normalized.substring(normalized.length() - tail);
	}

	private static ChatOptions options(AppSettings settings) {
		if (settings.provider() == LlmProvider.OLLAMA) {
			OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
				.model(settings.model())
				.temperature(0.1)
				.topP(0.9)
				.numCtx(Math.min(settings.numCtx(), 16384))
				.numPredict(MAX_TOKENS);
			builder.disableThinking();
			return builder.build();
		}
		return OpenAiChatOptions.builder()
			.model(settings.model())
			.temperature(0.1)
			.topP(0.9)
			.maxTokens(MAX_TOKENS)
			.build();
	}

}
