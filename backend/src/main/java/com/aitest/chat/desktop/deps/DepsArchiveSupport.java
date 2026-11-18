package com.aitest.chat.desktop.deps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 下载安装包与解压 zip 的共用工具。 */
final class DepsArchiveSupport {

	private static final Duration CONNECT = Duration.ofSeconds(30);

	private static final Duration REQUEST = Duration.ofMinutes(20);

	private DepsArchiveSupport() {
	}

	/** 下载 URL 到目标文件（覆盖）。 */
	static Path download(String url, Path target) throws IOException, InterruptedException {
		Files.createDirectories(target.getParent());
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST).GET().build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("下载失败 HTTP " + response.statusCode() + "：" + url);
		}
		try (InputStream in = response.body()) {
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
		if (Files.size(target) < 10_000L) {
			Files.deleteIfExists(target);
			throw new IllegalStateException("下载文件过小：" + url);
		}
		return target;
	}

	/** GET 文本内容（用于读取版本 JSON）。 */
	static String downloadText(String url) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("请求失败 HTTP " + response.statusCode() + "：" + url);
		}
		return response.body() == null ? "" : response.body();
	}

	/** 解压 zip 到目录（先清空目标）。 */
	static void unzip(Path zip, Path destination) throws IOException {
		deleteRecursively(destination);
		Files.createDirectories(destination);
		try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = zin.getNextEntry()) != null) {
				Path out = destination.resolve(entry.getName()).normalize();
				if (!out.startsWith(destination)) {
					throw new IOException("非法 zip 路径：" + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(out);
				}
				else {
					Files.createDirectories(out.getParent());
					try (OutputStream os = Files.newOutputStream(out)) {
						zin.transferTo(os);
					}
				}
				zin.closeEntry();
			}
		}
	}

	/** 若 zip 内只有一层目录，把内容提升到目标根。 */
	static void flattenSingleRoot(Path destination) throws IOException {
		try (var stream = Files.list(destination)) {
			var children = stream.toList();
			if (children.size() != 1 || !Files.isDirectory(children.get(0))) {
				return;
			}
			Path only = children.get(0);
			try (var nested = Files.list(only)) {
				for (Path child : nested.toList()) {
					Files.move(child, destination.resolve(child.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.deleteIfExists(only);
		}
	}

	/** 递归删除目录。 */
	static void deleteRecursively(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/** 按路径深度倒序删除（备用）。 */
	static void deleteQuietly(Path root) {
		try {
			if (!Files.exists(root)) {
				return;
			}
			try (var walk = Files.walk(root)) {
				walk.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					}
					catch (IOException ignored) {
						/* 忽略清理失败 */
					}
				});
			}
		}
		catch (IOException ignored) {
			/* 忽略清理失败 */
		}
	}

}
