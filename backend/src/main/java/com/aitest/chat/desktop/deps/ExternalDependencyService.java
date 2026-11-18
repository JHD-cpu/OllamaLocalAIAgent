package com.aitest.chat.desktop.deps;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aitest.chat.desktop.InstalledSoftwareCatalog;
import com.aitest.chat.knowledge.indexing.PaddleAvailability;
import com.aitest.chat.llm.ollama.OllamaAdminClient;
import com.aitest.chat.llm.ollama.OllamaInfo;
import com.aitest.chat.llm.ollama.OllamaModel;
import com.aitest.chat.settings.SettingsService;

/**
 * 检测 Python / PaddleOCR / Node / 嵌入模型是否可用。
 * 已取消所有自动下载与安装；用户需在本机自行准备依赖。
 */
@Service
public class ExternalDependencyService {

	private static final Logger log = LoggerFactory.getLogger(ExternalDependencyService.class);

	private static final String PYTHON_SETUP_URL = "https://www.python.org/ftp/python/3.12.8/python-3.12.8-amd64.exe";

	private static final String NODE_ZIP_URL = "https://nodejs.org/dist/v22.14.0/node-v22.14.0-win-x64.zip";

	private final InstalledSoftwareCatalog softwareCatalog;

	private final ManagedDepsLayout managedDeps;

	private final PaddleAvailability paddleAvailability;

	private final OllamaAdminClient ollamaAdminClient;

	private final SettingsService settingsService;

	private final Map<ExternalDependencyId, AtomicReference<ExternalDependencyStatus>> statuses = new EnumMap<>(
			ExternalDependencyId.class);

	private final Map<ExternalDependencyId, Thread> workers = new EnumMap<>(ExternalDependencyId.class);

	private final Object lock = new Object();

	/** 注入软件目录、本地 deps 布局、OCR 探测、Ollama 与配置。 */
	public ExternalDependencyService(InstalledSoftwareCatalog softwareCatalog, ManagedDepsLayout managedDeps,
			PaddleAvailability paddleAvailability, OllamaAdminClient ollamaAdminClient, SettingsService settingsService) {
		this.softwareCatalog = softwareCatalog;
		this.managedDeps = managedDeps;
		this.paddleAvailability = paddleAvailability;
		this.ollamaAdminClient = ollamaAdminClient;
		this.settingsService = settingsService;
		for (ExternalDependencyId id : ExternalDependencyId.values()) {
			this.statuses.put(id, new AtomicReference<>(idleOf(id)));
		}
	}

	/** 返回全部依赖的当前状态快照。 */
	public List<ExternalDependencyStatus> list() {
		List<ExternalDependencyStatus> result = new ArrayList<>();
		for (ExternalDependencyId id : ExternalDependencyId.values()) {
			result.add(status(id));
		}
		return result;
	}

	/** 仅返回尚未安装且可自动安装的依赖。 */
	public List<ExternalDependencyStatus> missingInstallable() {
		List<ExternalDependencyStatus> result = new ArrayList<>();
		for (ExternalDependencyStatus item : list()) {
			if (!item.installed() && item.installable()) {
				result.add(item);
			}
		}
		return result;
	}

	/** 查询单个依赖状态；空闲时会重新探测。 */
	public ExternalDependencyStatus status(ExternalDependencyId id) {
		AtomicReference<ExternalDependencyStatus> ref = this.statuses.get(id);
		ExternalDependencyStatus current = ref.get();
		if (current.busy()) {
			return current;
		}
		ExternalDependencyStatus fresh = idleOf(id);
		ref.set(fresh);
		return fresh;
	}

	/** 已禁用自动安装。 */
	public ExternalDependencyStatus startInstall(ExternalDependencyId id) {
		throw new IllegalStateException("已取消自动下载与安装，请自行安装 " + displayName(id) + "。");
	}

	/** 后台安装入口。 */
	private void runInstall(ExternalDependencyId id) {
		try {
			switch (id) {
				case PYTHON -> installPython();
				case PADDLE_OCR -> installPaddleOcr();
				case NODE -> installNode();
				case EMBEDDING_MODEL -> installEmbeddingModel();
			}
			this.softwareCatalog.refresh();
			if (id == ExternalDependencyId.PYTHON || id == ExternalDependencyId.PADDLE_OCR) {
				this.paddleAvailability.resetAvailability();
			}
			ExternalDependencyStatus done = idleOf(id);
			if (!done.installed()) {
				publish(id, "error", displayName(id) + " 安装结束但仍未检测到，请手动安装后重试。", false, false, "");
				return;
			}
			publish(id, "ready", displayName(id) + " 已就绪", false, true, done.path());
		}
		catch (Exception ex) {
			log.warn("安装 {} 失败：{}", id, ex.getMessage());
			publish(id, "error", "安装失败：" + abbreviate(ex.getMessage()), false, false, "");
		}
	}

	/** 将官方 Python 安装到 deps/python。 */
	private void installPython() throws IOException, InterruptedException {
		this.managedDeps.ensureRoot();
		Path target = this.managedDeps.pythonDir().toAbsolutePath().normalize();
		publish(ExternalDependencyId.PYTHON, "downloading", "正在下载 Python 到 " + target + " …", true, false, target.toString());
		Path setup = Files.createTempFile("python-setup-", ".exe");
		try {
			DepsArchiveSupport.download(PYTHON_SETUP_URL, setup);
			publish(ExternalDependencyId.PYTHON, "installing", "正在安装 Python 到应用目录…", true, false, target.toString());
			DepsArchiveSupport.deleteRecursively(target);
			Files.createDirectories(target);
			runCommand(List.of(setup.toString(), "/quiet", "InstallAllUsers=0", "PrependPath=0", "Include_test=0",
					"Include_launcher=0", "SimpleInstall=1", "TargetDir=" + target), Duration.ofMinutes(10), true);
		}
		finally {
			Files.deleteIfExists(setup);
		}
		if (blank(this.managedDeps.pythonExe())) {
			throw new IllegalStateException("Python 安装后未找到 " + target.resolve("python.exe"));
		}
	}

	/** 向应用目录中的 Python 安装 paddleocr。 */
	private void installPaddleOcr() throws IOException, InterruptedException {
		String python = requirePython();
		publish(ExternalDependencyId.PADDLE_OCR, "installing", "正在向应用内 Python 安装 PaddleOCR…", true, false, python);
		runCommand(List.of(python, "-m", "pip", "install", "--upgrade", "pip"), Duration.ofMinutes(5), true);
		runCommand(List.of(python, "-m", "pip", "install", "paddlepaddle", "paddleocr"), Duration.ofMinutes(25), true);
		this.paddleAvailability.resetAvailability();
		String reason = this.paddleAvailability.probeReason();
		if (reason != null) {
			throw new IllegalStateException(reason);
		}
	}

	/** 下载 Node 便携包到 deps/node。 */
	private void installNode() throws IOException, InterruptedException {
		this.managedDeps.ensureRoot();
		Path target = this.managedDeps.nodeDir().toAbsolutePath().normalize();
		publish(ExternalDependencyId.NODE, "downloading", "正在下载 Node.js 到 " + target + " …", true, false, target.toString());
		Path zip = Files.createTempFile("node-", ".zip");
		try {
			DepsArchiveSupport.download(NODE_ZIP_URL, zip);
			publish(ExternalDependencyId.NODE, "installing", "正在解压 Node.js 到应用目录…", true, false, target.toString());
			DepsArchiveSupport.unzip(zip, target);
			DepsArchiveSupport.flattenSingleRoot(target);
		}
		finally {
			Files.deleteIfExists(zip);
		}
		if (blank(this.managedDeps.nodeExe())) {
			throw new IllegalStateException("Node.js 解压后未找到 node.exe");
		}
	}

	/** 用 ollama pull 拉取嵌入模型（模型文件由 Ollama 管理，可执行文件仍在 deps/ollama）。 */
	private void installEmbeddingModel() throws IOException, InterruptedException {
		String model = this.settingsService.get().embeddingModel();
		String exe = requireOllamaExe();
		publish(ExternalDependencyId.EMBEDDING_MODEL, "downloading", "正在拉取嵌入模型 " + model + "…", true, false, exe);
		runCommand(List.of(exe, "pull", model), Duration.ofMinutes(30), true);
		if (!embeddingInstalled(model)) {
			throw new IllegalStateException("拉取完成但仍未在 Ollama 中看到模型 " + model);
		}
	}

	/** 组装空闲状态（重新探测）。 */
	private ExternalDependencyStatus idleOf(ExternalDependencyId id) {
		return switch (id) {
			case PYTHON -> {
				String path = this.softwareCatalog.pythonExe();
				boolean ok = !blank(path) && Files.isRegularFile(Path.of(path));
				yield statusOf(id, ok, false, ok ? path : this.managedDeps.pythonDir().toString(),
						ok ? "已找到 Python" : "未检测到 Python（请自行安装，应用不自动下载）");
			}
			case PADDLE_OCR -> {
				boolean pythonOk = !blank(this.softwareCatalog.pythonExe());
				String reason = pythonOk ? this.paddleAvailability.probeReason() : "需要先安装 Python";
				boolean ok = reason == null;
				yield statusOf(id, ok, false, blankToEmpty(this.softwareCatalog.pythonExe()),
						ok ? "PaddleOCR 可用" : ((reason == null ? "PaddleOCR 不可用" : reason) + "（请自行安装）"));
			}
			case NODE -> {
				String path = this.softwareCatalog.nodeExe();
				boolean ok = !blank(path) && Files.isRegularFile(Path.of(path));
				yield statusOf(id, ok, false, ok ? path : this.managedDeps.nodeDir().toString(),
						ok ? "已找到 Node.js" : "未检测到 Node.js（请自行安装，应用不自动下载）");
			}
			case EMBEDDING_MODEL -> {
				var settings = this.settingsService.get();
				String model = settings.embeddingModel();
				if (settings.provider().remote()) {
					yield statusOf(id, true, false, model, "远程提供方使用接口侧嵌入模型（" + model + "）");
				}
				boolean ollamaUp = this.ollamaAdminClient.inspect(settings.baseUrl()).ok();
				boolean ok = ollamaUp && embeddingInstalled(model);
				yield statusOf(id, ok, false, model,
						ok ? "嵌入模型已就绪" : "未找到嵌入模型 " + model + "（请自行 ollama pull，应用不自动下载）");
			}
		};
	}

	private ExternalDependencyStatus statusOf(ExternalDependencyId id, boolean installed, boolean installable, String path,
			String message) {
		return new ExternalDependencyStatus(id.name(), displayName(id), description(id), installed, installable, false, "idle",
				message, blankToEmpty(path));
	}

	private void publish(ExternalDependencyId id, String phase, String message, boolean busy, boolean installed, String path) {
		this.statuses.get(id)
			.set(new ExternalDependencyStatus(id.name(), displayName(id), description(id), installed, true, busy, phase, message,
					blankToEmpty(path)));
	}

	private void runCommand(List<String> command, Duration timeout, boolean failOnNonZero)
			throws IOException, InterruptedException {
		log.info("执行依赖安装命令 {}", command);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = readLimited(process.getInputStream(), 6000);
		boolean finished = process.waitFor(Math.max(1L, timeout.toMinutes()), TimeUnit.MINUTES);
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalStateException("命令超时：" + command.get(0));
		}
		int code = process.exitValue();
		log.info("命令结束 code={} output={}", code, abbreviate(output));
		if (failOnNonZero && code != 0) {
			throw new IllegalStateException("命令失败（" + code + "）：" + abbreviate(output));
		}
	}

	private boolean embeddingInstalled(String model) {
		if (blank(model)) {
			return false;
		}
		OllamaInfo info = this.ollamaAdminClient.inspect(this.settingsService.get().baseUrl());
		if (!info.ok() || info.models() == null) {
			return false;
		}
		String want = model.trim().toLowerCase(Locale.ROOT);
		for (OllamaModel item : info.models()) {
			if (item.name() != null && (item.name().equalsIgnoreCase(want) || item.name().toLowerCase(Locale.ROOT).startsWith(want + ":"))) {
				return true;
			}
		}
		return false;
	}

	private String requirePython() {
		this.softwareCatalog.refresh();
		String python = firstNonBlank(this.managedDeps.pythonExe(), this.softwareCatalog.pythonExe());
		if (blank(python) || !Files.isRegularFile(Path.of(python))) {
			throw new IllegalStateException("未找到 Python，请先安装到 " + this.managedDeps.pythonDir());
		}
		return python;
	}

	private String requireOllamaExe() {
		String exe = firstNonBlank(this.managedDeps.ollamaExe(), this.softwareCatalog.ollamaExe());
		if (exe == null || !Files.isRegularFile(Path.of(exe))) {
			throw new IllegalStateException("未找到 ollama.exe，请先安装到 " + this.managedDeps.ollamaDir());
		}
		return exe;
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second != null && !second.isBlank() ? second : null;
	}

	private static String readLimited(InputStream in, int limit) throws IOException {
		byte[] buffer = in.readNBytes(Math.max(1, limit));
		return new String(buffer, StandardCharsets.UTF_8);
	}

	private static String displayName(ExternalDependencyId id) {
		return switch (id) {
			case PYTHON -> "Python";
			case PADDLE_OCR -> "PaddleOCR";
			case NODE -> "Node.js";
			case EMBEDDING_MODEL -> "嵌入模型";
		};
	}

	private static String description(ExternalDependencyId id) {
		return switch (id) {
			case PYTHON -> "扫描版 PDF OCR、部分代码工具需要；安装到应用 deps/python";
			case PADDLE_OCR -> "扫描版 PDF 文字识别需要；安装到应用内 Python";
			case NODE -> "运行工作区 JavaScript 文件需要；安装到应用 deps/node";
			case EMBEDDING_MODEL -> "知识库向量检索需要；由 Ollama 拉取模型";
		};
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String blankToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String abbreviate(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.replace('\n', ' ').trim();
		return trimmed.length() > 240 ? trimmed.substring(0, 240) : trimmed;
	}

}
