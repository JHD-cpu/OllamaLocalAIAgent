package com.aitest.chat.desktop.deps;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 应用自管依赖目录布局：一律装在 {@code deps/} 下，不写系统目录。
 * 默认相对 {@code user.dir}；安装/桌面模式由 InstalledMode 设为用户主目录下的 deps。
 */
@Component
public class ManagedDepsLayout {

	private final Path root;

	/** 读取 app.deps-dir，缺省为 user.dir/deps。 */
	public ManagedDepsLayout(@Value("${app.deps-dir:}") String configured) {
		if (configured != null && !configured.isBlank()) {
			this.root = Path.of(configured.trim()).toAbsolutePath().normalize();
		}
		else {
			this.root = Path.of(System.getProperty("user.dir", ".")).resolve("deps").toAbsolutePath().normalize();
		}
	}

	/** 依赖根目录（python/node/ollama 的父目录）。 */
	public Path root() {
		return this.root;
	}

	/** 确保依赖根目录存在。 */
	public Path ensureRoot() {
		try {
			Files.createDirectories(this.root);
		}
		catch (Exception ex) {
			throw new IllegalStateException("无法创建依赖目录 " + this.root + "：" + ex.getMessage(), ex);
		}
		return this.root;
	}

	public Path pythonDir() {
		return this.root.resolve("python");
	}

	public Path nodeDir() {
		return this.root.resolve("node");
	}

	public Path ollamaDir() {
		return this.root.resolve("ollama");
	}

	/** 本地 Python 解释器（若已安装）。 */
	public String pythonExe() {
		return firstExisting(this.pythonDir().resolve("python.exe"), this.pythonDir().resolve("python3.exe"));
	}

	/** 本地 Node 解释器（若已安装）。 */
	public String nodeExe() {
		return firstExisting(this.nodeDir().resolve("node.exe"));
	}

	/** 本地 Ollama（若已安装到 deps/ollama）。 */
	public String ollamaExe() {
		String direct = firstExisting(this.ollamaDir().resolve("ollama.exe"));
		if (direct != null && !direct.toLowerCase(Locale.ROOT).contains("ollamachat")) {
			return direct;
		}
		return findUnder(this.ollamaDir(), "ollama.exe", 3);
	}

	private static String firstExisting(Path... paths) {
		for (Path path : paths) {
			if (path != null && Files.isRegularFile(path)) {
				return path.toAbsolutePath().normalize().toString();
			}
		}
		return null;
	}

	private static String findUnder(Path root, String fileName, int maxDepth) {
		if (root == null || !Files.isDirectory(root)) {
			return null;
		}
		try (var stream = Files.walk(root, Math.max(1, maxDepth))) {
			return stream.filter(Files::isRegularFile)
				.filter(path -> fileName.equalsIgnoreCase(path.getFileName().toString()))
				.map(path -> path.toAbsolutePath().normalize().toString())
				.filter(path -> !path.toLowerCase(Locale.ROOT).contains("ollamachat"))
				.findFirst()
				.orElse(null);
		}
		catch (Exception ex) {
			return null;
		}
	}

}
