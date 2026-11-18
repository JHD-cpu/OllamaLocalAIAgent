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

/** 截取全屏并保存到应用数据目录。 */
public class ScreenshotTool {

	private static final Logger log = LoggerFactory.getLogger(ScreenshotTool.class);

	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private final ManagedDepsLayout depsLayout;

	public ScreenshotTool(ManagedDepsLayout depsLayout) {
		this.depsLayout = depsLayout;
	}

	@Tool(name = "take_screenshot", description = "截取当前屏幕全屏画面，保存为 PNG 并返回文件路径。需要看屏幕内容时调用。")
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
			// 安装模式下 deps 在 %APPDATA%/OllamaChat/deps，截图放同级 screenshots
			Files.createDirectories(dir);
			Path file = dir.resolve("shot-" + LocalDateTime.now().format(FILE_TIME) + ".png");
			ImageIO.write(image, "png", file.toFile());
			log.info("截屏已保存 {}", file);
			return "截屏已保存：" + file.toAbsolutePath().normalize()
					+ "。若使用视觉模型，可让用户在对话中附上该图片，或说明路径。";
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
