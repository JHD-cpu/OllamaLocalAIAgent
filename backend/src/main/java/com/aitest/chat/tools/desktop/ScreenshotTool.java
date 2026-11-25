package com.aitest.chat.tools.desktop;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

import com.aitest.chat.desktop.deps.ManagedDepsLayout;
import com.aitest.chat.knowledge.indexing.LlmPageOcr;

/** 截取全屏，保存 PNG，并用视觉模型描述内容供对话使用。 */
public class ScreenshotTool {

	private static final Logger log = LoggerFactory.getLogger(ScreenshotTool.class);

	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final ManagedDepsLayout depsLayout;

	private final LlmPageOcr vision;

	public ScreenshotTool(ManagedDepsLayout depsLayout, LlmPageOcr vision) {
		this.depsLayout = depsLayout;
		this.vision = vision;
	}

	@Tool(name = "take_screenshot", description = "截取当前屏幕全屏，保存 PNG，并用视觉模型描述画面内容。需要看屏幕时调用。")
	public String takeScreenshot() {
		if (GraphicsEnvironment.isHeadless()) {
			return "当前环境无图形界面，无法截屏。";
		}
		try {
			Robot robot = new Robot();
			Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
			BufferedImage image = robot.createScreenCapture(screen);
			Path dir = this.depsLayout.root().getParent() == null
					? Path.of(System.getProperty("user.dir", ".")).resolve("screenshots")
					: this.depsLayout.root().getParent().resolve("screenshots");
			Files.createDirectories(dir);
			Path file = dir.resolve("shot-" + LocalDateTime.now().format(FILE_TIME) + ".png");
			ImageIO.write(image, "png", file.toFile());
			log.info("截屏已保存 {}", file);
			String description = this.vision == null ? "" : this.vision.describeScreen(image);
			StringBuilder out = new StringBuilder();
			out.append("截屏已保存：").append(file.toAbsolutePath().normalize());
			if (description != null && !description.isBlank()) {
				out.append("\n\n【屏幕内容】\n").append(description.trim());
			}
			else {
				out.append("\n视觉描述不可用；可让用户在对话中直接粘贴该截图。");
			}
			return out.toString();
		}
		catch (Exception ex) {
			log.warn("截屏失败：{}", ex.getMessage());
			return "截屏失败：" + (ex.getMessage() == null ? "未知错误" : ex.getMessage());
		}
	}

	/** 将 PNG 文件读为 data URL（供后续多模态扩展）。 */
	public static String toDataUrl(Path png) throws Exception {
		byte[] bytes = Files.readAllBytes(png);
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
	}

}
