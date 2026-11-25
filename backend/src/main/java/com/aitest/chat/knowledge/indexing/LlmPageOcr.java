package com.aitest.chat.knowledge.indexing;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import com.aitest.chat.llm.ChatModelFactory;
import com.aitest.chat.llm.LlmProvider;
import com.aitest.chat.llm.ollama.OllamaAdminClient;
import com.aitest.chat.llm.ollama.OllamaModel;
import com.aitest.chat.settings.AppSettings;
import com.aitest.chat.settings.SettingsService;

/**
 * 扫描版 PDF 页图 OCR：优先用本机 Ollama 视觉模型，没有则用当前默认对话模型。
 */
@Component
public class LlmPageOcr implements PdfTextExtractor.PageOcr {

	private static final Logger log = LoggerFactory.getLogger(LlmPageOcr.class);

	/** 大文档 OCR 降边长，减少视觉模型耗时与显存。 */
	private static final int MAX_EDGE = 1280;

	private static final int MAX_TOKENS = 3072;

	private static final String PROMPT = """
			请完整识别这张图片中的全部文字，严格按阅读顺序输出纯文本。
			不要翻译，不要解释，不要添加标题或前后缀。
			若几乎没有文字，只输出空字符串。
			""".strip();

	private final ChatModelFactory chatModelFactory;

	private final SettingsService settingsService;

	private final OllamaAdminClient ollamaAdminClient;

	LlmPageOcr(
		ChatModelFactory chatModelFactory,
		SettingsService settingsService,
		OllamaAdminClient ollamaAdminClient
	) {
		this.chatModelFactory = chatModelFactory;
		this.settingsService = settingsService;
		this.ollamaAdminClient = ollamaAdminClient;
	}

	private static final String SCREEN_PROMPT = """
			请描述这张屏幕截图的主要内容：可见窗口、文字、按钮和关键状态。
			优先摘录关键中的关键可读文字；不确定处标明「看不清」。
			用简洁中文，不要编造看不到的内容。
			""".strip();

	@Override
	public String read(BufferedImage image) {
		return callVision(image, PROMPT, "pdf-page.png", true);
	}

	/** 截屏场景：用视觉模型描述界面，供对话工具回传。 */
	public String describeScreen(BufferedImage image) {
		return callVision(image, SCREEN_PROMPT, "screenshot.png", false);
	}

	private String callVision(BufferedImage image, String promptText, String mediaName, boolean failHard) {
		if (image == null) {
			return "";
		}
		AppSettings settings = this.settingsService.get();
		String model = resolveModel(settings);
		long started = System.nanoTime();
		try {
			byte[] png = toPng(scaleDown(image));
			Media media = Media.builder()
				.mimeType(MimeTypeUtils.IMAGE_PNG)
				.data(png)
				.name(mediaName)
				.build();
			UserMessage message = UserMessage.builder().text(promptText).media(List.of(media)).build();
			ChatModel chatModel = this.chatModelFactory.get(settings);
			Prompt prompt = new Prompt(List.of(message), options(settings, model));
			ChatResponse response = chatModel.call(prompt);
			String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
				? ""
				: response.getResult().getOutput().getText();
			String cleaned = clean(text);
			log.info("LLM 视觉完成 model={} chars={} 耗时={}ms",
				model, cleaned.length(), (System.nanoTime() - started) / 1_000_000L);
			return cleaned;
		}
		catch (Exception ex) {
			log.warn("LLM 视觉失败 model={}: {}", model, ex.toString());
			if (!failHard) {
				return "视觉识别失败（" + model + "）：" + brief(ex);
			}
			throw new IllegalStateException(
				"扫描版 PDF 识别失败（模型 " + model + "）："
					+ brief(ex)
					+ "。请确认本地 Ollama 可用，并尽量使用带视觉能力的模型（如 llava / qwen2.5-vl / minicpm-v）；"
					+ "若暂无视觉模型，将使用当前默认模型尝试识别。",
				ex
			);
		}
	}

	/** 本机有视觉模型则优先；否则用设置里的默认模型。 */
	String resolveModel(AppSettings settings) {
		String fallback = settings.model() == null ? "" : settings.model().trim();
		if (settings.provider() != LlmProvider.OLLAMA) {
			return fallback;
		}
		if (looksVision(fallback, "")) {
			return fallback;
		}
		try {
			List<OllamaModel> models = this.ollamaAdminClient.inspect(settings.baseUrl()).models();
			for (OllamaModel model : models) {
				if (model != null && looksVision(model.name(), model.family())) {
					log.info("扫描 OCR 选用本机视觉模型 {}（默认模型为 {}）", model.name(), fallback);
					return model.name();
				}
			}
		}
		catch (Exception ex) {
			log.warn("列举 Ollama 模型失败，改用默认模型 {}: {}", fallback, ex.toString());
		}
		log.info("未找到本机视觉模型，扫描 OCR 使用默认模型 {}", fallback);
		return fallback;
	}

	static boolean looksVision(String name, String family) {
		String blob = ((name == null ? "" : name) + " " + (family == null ? "" : family)).toLowerCase();
		if (blob.isBlank()) {
			return false;
		}
		return blob.contains("vision")
			|| blob.contains("llava")
			|| blob.contains("bakllava")
			|| blob.contains("moondream")
			|| blob.contains("minicpm")
			|| blob.contains("qwen2-vl")
			|| blob.contains("qwen2.5-vl")
			|| blob.contains("qwen2vl")
			|| blob.contains("qwen2.5vl")
			|| blob.contains("internvl")
			|| blob.contains("-vl")
			|| blob.contains(":vl")
			|| blob.contains("gemma3");
	}

	private static ChatOptions options(AppSettings settings, String model) {
		if (settings.provider() == LlmProvider.OLLAMA) {
			OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
				.model(model)
				.temperature(0.0)
				.topP(0.9)
				.numCtx(Math.min(settings.numCtx(), 16384))
				.numPredict(MAX_TOKENS);
			builder.disableThinking();
			return builder.build();
		}
		return OpenAiChatOptions.builder()
			.model(model)
			.temperature(0.0)
			.topP(0.9)
			.maxTokens(MAX_TOKENS)
			.build();
	}

	private static BufferedImage scaleDown(BufferedImage source) {
		int width = source.getWidth();
		int height = source.getHeight();
		int edge = Math.max(width, height);
		if (edge <= MAX_EDGE) {
			return source;
		}
		double scale = (double) MAX_EDGE / edge;
		int w = Math.max(1, (int) Math.round(width * scale));
		int h = Math.max(1, (int) Math.round(height * scale));
		BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = scaled.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(source, 0, 0, w, h, null);
		}
		finally {
			g.dispose();
		}
		return scaled;
	}

	private static byte[] toPng(BufferedImage image) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "png", out)) {
			throw new IllegalStateException("无法编码页面图片");
		}
		return out.toByteArray();
	}

	private static String clean(String text) {
		if (text == null) {
			return "";
		}
		String trimmed = text.trim();
		if (trimmed.startsWith("```")) {
			int firstNl = trimmed.indexOf('\n');
			if (firstNl > 0) {
				trimmed = trimmed.substring(firstNl + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3);
			}
			trimmed = trimmed.trim();
		}
		return trimmed;
	}

	private static String brief(Throwable ex) {
		String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage().replace('\n', ' ').trim();
		return message.length() > 240 ? message.substring(0, 240) : message;
	}

}
