package com.aitest.chat.rag;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 用常驻的 PaddleOCR 进程识别扫描页。模型只加载一次，之后每页送一张图片路径。
 */
@Component
class PaddlePageOcr implements PdfTextExtractor.PageOcr, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(PaddlePageOcr.class);

	private final RagProperties properties;

	private final Object lock = new Object();

	private Process process;

	private BufferedWriter writer;

	private BufferedReader reader;

	private final StringBuilder errors = new StringBuilder();

	PaddlePageOcr(RagProperties properties) {
		this.properties = properties;
	}

	@Override
	public String read(BufferedImage image) {
		Path imageFile = null;
		try {
			imageFile = Files.createTempFile("paddle-ocr-", ".png");
			ImageIO.write(image, "png", imageFile.toFile());
			synchronized (this.lock) {
				start();
				this.writer.write(imageFile.toString());
				this.writer.newLine();
				this.writer.flush();
				StringBuilder text = new StringBuilder();
				String line;
				while ((line = this.reader.readLine()) != null) {
					if ("---END---".equals(line)) {
						break;
					}
					if (line.startsWith("---ERROR---")) {
						throw new IllegalStateException("PaddleOCR 失败：" + line.substring("---ERROR---".length()).trim());
					}
					if (text.length() > 0) {
						text.append('\n');
					}
					text.append(line);
				}
				if (line == null) {
					String detail = this.errors.toString().trim();
					stop();
					throw new IllegalStateException(detail.isEmpty() ? "PaddleOCR 进程已退出。" : "PaddleOCR 进程已退出：" + brief(detail));
				}
				return text.toString().trim();
			}
		}
		catch (IOException ex) {
			stop();
			throw new IllegalStateException("无法调用 PaddleOCR：" + ex.getMessage(), ex);
		}
		finally {
			if (imageFile != null) {
				try {
					Files.deleteIfExists(imageFile);
				}
				catch (IOException ignored) {
					log.warn("删除临时图片失败 {}", imageFile);
				}
			}
		}
	}

	private void start() throws IOException {
		if (this.process != null && this.process.isAlive()) {
			return;
		}
		Path script = script();
		if (!Files.isRegularFile(script)) {
			throw new IllegalStateException("找不到 PaddleOCR 脚本：" + script);
		}
		String python = this.properties.getPaddlePython();
		this.errors.setLength(0);
		ProcessBuilder builder = new ProcessBuilder(python, "-u", script.toString());
		builder.environment().put("PYTHONIOENCODING", "utf-8");
		builder.environment().put("PYTHONUNBUFFERED", "1");
		builder.environment().put("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True");
		// Windows CPU 的 oneDNN 跑 PP-OCRv6 会失败，关掉后改用普通 CPU 内核。
		builder.environment().put("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "False");
		builder.environment().put("FLAGS_use_mkldnn", "0");
		this.process = builder.start();
		this.writer = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
		this.reader = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
		Thread drain = new Thread(() -> drain(this.process), "paddle-ocr-err");
		drain.setDaemon(true);
		drain.start();
	}

	private void drain(Process current) {
		try (BufferedReader errors = new BufferedReader(
				new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = errors.readLine()) != null) {
				synchronized (this.errors) {
					if (this.errors.length() < 4000) {
						this.errors.append(line).append('\n');
					}
				}
			}
		}
		catch (IOException ignored) {
			log.warn("读取 PaddleOCR 日志失败");
		}
	}

	/** 从工作目录向上找脚本，兼容在仓库根目录或 backend 目录启动。 */
	private Path script() {
		String configured = this.properties.getPaddleScript();
		Path given = Path.of(configured == null || configured.isBlank() ? "scripts/paddle_ocr.py" : configured);
		if (given.isAbsolute()) {
			return given;
		}
		Path dir = Path.of("").toAbsolutePath();
		for (int i = 0; i < 4 && dir != null; i++) {
			Path direct = dir.resolve(given);
			if (Files.isRegularFile(direct)) {
				return direct;
			}
			Path nested = dir.resolve("backend").resolve(given);
			if (Files.isRegularFile(nested)) {
				return nested;
			}
			dir = dir.getParent();
		}
		return Path.of("").toAbsolutePath().resolve(given);
	}

	private static String brief(String value) {
		String trimmed = value.replace('\n', ' ').trim();
		return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
	}

	private void stop() {
		Process current = this.process;
		this.process = null;
		this.writer = null;
		this.reader = null;
		if (current != null) {
			current.destroy();
			try {
				current.waitFor(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			current.destroyForcibly();
		}
	}

	@Override
	public void destroy() {
		synchronized (this.lock) {
			stop();
		}
	}

}
