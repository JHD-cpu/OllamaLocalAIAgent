package com.aitest.chat.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/**
 * 文本库。原件、整篇纯文本和切块都放在 app.rag.text-dir 下面，不进数据库。
 */
@Component
class TextStore {

	private final Path root;

	TextStore(RagProperties properties) {
		this.root = Path.of(properties.getTextDir());
		try {
			Files.createDirectories(this.root.resolve("originals"));
			Files.createDirectories(this.root.resolve("texts"));
			Files.createDirectories(this.root.resolve("chunks"));
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法创建文本库目录 " + this.root, ex);
		}
	}

	Path writeOriginal(String id, String filename, byte[] bytes) {
		Path path = this.root.resolve("originals").resolve(id).resolve(safeName(filename));
		write(path, bytes);
		return path;
	}

	Path writeText(String id, String text) {
		Path path = this.root.resolve("texts").resolve(id + ".txt");
		write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return path;
	}

	Path writeChunk(String id, int index, String text) {
		Path path = this.root.resolve("chunks").resolve(id).resolve(index + ".txt");
		write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return path;
	}

	/** 删掉这个文档在文本库里的原件、全文和切块。目录不存在时忽略。 */
	void deleteAll(String id) {
		deleteTree(this.root.resolve("originals").resolve(id));
		deleteTree(this.root.resolve("chunks").resolve(id));
		try {
			Files.deleteIfExists(this.root.resolve("texts").resolve(id + ".txt"));
		}
		catch (IOException ex) {
			throw new IllegalStateException("删除文本失败", ex);
		}
	}

	private static void write(Path path, byte[] bytes) {
		try {
			Files.createDirectories(path.getParent());
			Files.write(path, bytes);
		}
		catch (IOException ex) {
			throw new IllegalStateException("写入文本库失败：" + path, ex);
		}
	}

	private static void deleteTree(Path path) {
		if (!Files.exists(path)) {
			return;
		}
		try (var walk = Files.walk(path)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
				try {
					Files.deleteIfExists(file);
				}
				catch (IOException ex) {
					throw new IllegalStateException("删除文本失败：" + file, ex);
				}
			});
		}
		catch (IOException ex) {
			throw new IllegalStateException("删除文本失败：" + path, ex);
		}
	}

	/** 只保留文件名，去掉路径和 Windows 不允许的字符。 */
	private static String safeName(String filename) {
		String name = filename == null ? "" : Path.of(filename).getFileName().toString();
		name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		if (name.isBlank()) {
			name = "file";
		}
		return name.length() > 120 ? name.substring(name.length() - 120) : name;
	}

}
