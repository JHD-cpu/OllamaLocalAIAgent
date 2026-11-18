package com.aitest.chat.tools.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.aitest.chat.desktop.InstalledSoftwareCatalog;

/** 按名称打开本机已安装程序。 */
public class OpenAppTool {

	private static final Logger log = LoggerFactory.getLogger(OpenAppTool.class);

	private final InstalledSoftwareCatalog catalog;

	public OpenAppTool(InstalledSoftwareCatalog catalog) {
		this.catalog = catalog;
	}

	@Tool(name = "open_app", description = "按名称打开本机已安装的程序或系统工具（如记事本、计算器）。打开网站请用 open_browser，不要用本工具。")
	public String openApp(@ToolParam(description = "程序名称，例如 记事本、Chrome、微信", required = true) String name) {
		String query = name == null ? "" : name.trim();
		if (query.isEmpty()) {
			return "请提供要打开的程序名称。";
		}
		String path = this.catalog.findLaunchPath(query);
		if (path == null || path.isBlank()) {
			List<String> suggest = this.catalog.suggestNames(query);
			if (suggest.isEmpty()) {
				return "未找到匹配的程序：" + query + "。可换更短的关键词，或用 run_cmd 执行 start 命令。";
			}
			return "未精确匹配「" + query + "」。候选：" + String.join("、", suggest);
		}
		try {
			launch(path);
			log.info("已打开程序 query={} path={}", query, path);
			return "已打开：" + path;
		}
		catch (Exception ex) {
			log.warn("打开程序失败 path={}: {}", path, ex.getMessage());
			return "打开失败：" + (ex.getMessage() == null ? "未知错误" : ex.getMessage());
		}
	}

	private static void launch(String path) throws IOException {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		List<String> command = new ArrayList<>();
		if (os.contains("win")) {
			Path file = Path.of(path);
			if (Files.isDirectory(file)) {
				command.addAll(List.of("cmd.exe", "/c", "start", "", path));
			}
			else if (path.toLowerCase(Locale.ROOT).endsWith(".exe") || !path.contains("\\") && !path.contains("/")) {
				command.addAll(List.of("cmd.exe", "/c", "start", "", path));
			}
			else {
				command.addAll(List.of("cmd.exe", "/c", "start", "", path));
			}
		}
		else {
			command.addAll(List.of("xdg-open", path));
		}
		new ProcessBuilder(command).start();
	}

}
