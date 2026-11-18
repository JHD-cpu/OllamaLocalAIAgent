package com.aitest.chat.llm.ollama;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aitest.chat.desktop.InstalledSoftwareCatalog;
import com.aitest.chat.desktop.deps.ManagedDepsLayout;

/**
 * 检测本机 Ollama、下载并安装到应用 deps/ollama 目录，再尝试启动服务。
 * 安装过程异步执行，前端通过 {@link #installStatus()} 轮询进度。
 */
@Service
public class OllamaLifecycleService {

	private static final Logger log = LoggerFactory.getLogger(OllamaLifecycleService.class);

	private static final String SETUP_URL = "https://ollama.com/download/OllamaSetup.exe";

	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

	private final InstalledSoftwareCatalog softwareCatalog;

	private final ManagedDepsLayout managedDeps;

	private final OllamaAdminClient adminClient;

	private final AtomicReference<OllamaInstallStatus> status = new AtomicReference<>(
			OllamaInstallStatus.idle(false, ""));

	private final Object lock = new Object();

	private volatile Thread worker;

	/** 注入软件目录、本地 deps 布局与连通性探测客户端。 */
	public OllamaLifecycleService(InstalledSoftwareCatalog softwareCatalog, ManagedDepsLayout managedDeps,
			OllamaAdminClient adminClient) {
		this.softwareCatalog = softwareCatalog;
		this.managedDeps = managedDeps;
		this.adminClient = adminClient;
		refreshIdleStatus();
	}

	/** 当前安装任务状态（可随时查询）。 */
	public OllamaInstallStatus installStatus() {
		OllamaInstallStatus current = this.status.get();
		if (current.busy()) {
			return current;
		}
		return refreshIdleStatus();
	}

	/**
	 * 汇总连通性与是否已安装。
	 * installed 以本机 ollama.exe 为准；ok 表示 HTTP 探测成功。
	 */
	public OllamaInfo inspectWithInstallHint(String baseUrl) {
		OllamaInfo info = this.adminClient.inspect(baseUrl);
		String exe = resolveExe();
		boolean installed = exe != null;
		String message = info.message();
		if (!info.ok()) {
			if (!installed) {
				message = "未检测到 Ollama。请自行安装并启动（应用不再自动下载）。";
			}
			else {
				message = "Ollama 已安装但未连接，请手动启动 ollama serve。";
			}
		}
		return new OllamaInfo(info.ok(), info.baseUrl(), info.version(), message, info.models(), installed,
				exe == null ? "" : exe, false);
	}

	/** 已禁用自动安装 Ollama。 */
	public OllamaInstallStatus startInstall() {
		throw new IllegalStateException("已取消自动下载与安装 Ollama，请自行安装。");
	}

	/** 若已安装则尝试启动 Ollama 服务并等待可连接。 */
	public OllamaInfo startService(String baseUrl) {
		String exe = resolveExe();
		if (exe == null) {
			this.softwareCatalog.refresh();
			exe = resolveExe();
		}
		if (exe == null) {
			throw new IllegalStateException("未找到 ollama.exe，请先安装 Ollama。");
		}
		publish("starting", "正在启动 Ollama…", true, true, exe);
		try {
			launchOllama(exe);
			OllamaInfo info = waitUntilReady(baseUrl, Duration.ofSeconds(45));
			String path = exe;
			if (info.ok()) {
				publish("ready", "Ollama 已启动", false, true, path);
			}
			else {
				publish("error", info.message(), false, true, path);
			}
			return inspectWithInstallHint(baseUrl);
		}
		catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			publish("error", "启动失败：" + ex.getMessage(), false, true, exe);
			throw new IllegalStateException("启动 Ollama 失败：" + ex.getMessage(), ex);
		}
	}

	/** 后台下载并安装到应用 deps/ollama，再启动。 */
	private void runInstall() {
		Path setup = null;
		try {
			this.managedDeps.ensureRoot();
			Path target = this.managedDeps.ollamaDir().toAbsolutePath().normalize();
			publish("downloading", "正在下载 Ollama 到 " + target + " …", true, false, "");
			setup = downloadSetup();
			publish("installing", "正在安装 Ollama 到应用目录…", true, false, target.toString());
			runSetup(setup, target);
			finishAfterInstall();
		}
		catch (Exception ex) {
			log.warn("自动安装 Ollama 失败：{}", ex.getMessage());
			publish("error", "安装失败：" + abbreviate(ex.getMessage()), false, resolveExe() != null, blankToEmpty(resolveExe()));
		}
		finally {
			if (setup != null) {
				try {
					Files.deleteIfExists(setup);
				}
				catch (IOException ignored) {
					/* 临时文件删不掉也不影响使用 */
				}
			}
		}
	}

	/** 安装结束后刷新路径、启动服务并更新状态。 */
	private void finishAfterInstall() throws IOException, InterruptedException {
		this.softwareCatalog.refresh();
		String exe = waitForExe(Duration.ofSeconds(90));
		if (exe == null) {
			throw new IllegalStateException("安装完成但仍未找到 ollama.exe，请手动打开 Ollama 后再试。");
		}
		publish("starting", "安装完成，正在启动 Ollama…", true, true, exe);
		launchOllama(exe);
		OllamaInfo info = waitUntilReady("http://127.0.0.1:11434", Duration.ofSeconds(60));
		if (!info.ok()) {
			throw new IllegalStateException(info.message());
		}
		publish("ready", "Ollama 已安装并启动（" + info.version() + "）", false, true, exe);
	}

	/** 下载官方 Windows 安装包到临时目录。 */
	private Path downloadSetup() throws IOException, InterruptedException {
		Path file = Files.createTempFile("OllamaSetup-", ".exe");
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		HttpRequest request = HttpRequest.newBuilder(URI.create(SETUP_URL)).timeout(DOWNLOAD_TIMEOUT).GET().build();
		HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			Files.deleteIfExists(file);
			throw new IllegalStateException("下载失败，HTTP " + response.statusCode());
		}
		try (InputStream in = response.body()) {
			Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
		}
		long size = Files.size(file);
		if (size < 1_000_000L) {
			Files.deleteIfExists(file);
			throw new IllegalStateException("下载的安装包过小，可能失败");
		}
		log.info("已下载 Ollama 安装包 {} ({} bytes)", file, size);
		return file;
	}

	/** 静默安装到指定应用目录（Inno /DIR）。 */
	private void runSetup(Path setup, Path targetDir) throws IOException, InterruptedException {
		Files.createDirectories(targetDir);
		Process process = new ProcessBuilder(setup.toAbsolutePath().toString(), "/VERYSILENT", "/NORESTART", "/SUPPRESSMSGBOXES",
				"/DIR=" + targetDir.toAbsolutePath().normalize())
			.redirectErrorStream(true)
			.start();
		String output = readLimited(process.getInputStream(), 2000);
		boolean finished = process.waitFor(10, TimeUnit.MINUTES);
		if (!finished) {
			process.destroyForcibly();
			throw new IllegalStateException("安装程序超时未结束");
		}
		int code = process.exitValue();
		log.info("OllamaSetup 结束 code={} dir={} output={}", code, targetDir, abbreviate(output));
		if (code != 0 && resolveExe() == null) {
			throw new IllegalStateException("安装程序退出码 " + code);
		}
	}

	/** 启动本机 Ollama（Windows 托盘应用 / serve）。 */
	private void launchOllama(String exe) throws IOException {
		Path path = Path.of(exe);
		Path app = path.getParent() == null ? null : path.getParent().resolve("ollama app.exe");
		ProcessBuilder builder;
		if (app != null && Files.isRegularFile(app)) {
			builder = new ProcessBuilder(app.toAbsolutePath().toString());
		}
		else {
			builder = new ProcessBuilder(exe, "serve");
		}
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		Process process = builder.start();
		log.info("已拉起 Ollama pid={} exe={}", process.pid(), builder.command());
	}

	/** 在时限内反复探测直到可连接。 */
	private OllamaInfo waitUntilReady(String baseUrl, Duration limit) throws InterruptedException {
		long deadline = System.nanoTime() + limit.toNanos();
		OllamaInfo last = this.adminClient.inspect(baseUrl);
		while (!last.ok() && System.nanoTime() < deadline) {
			Thread.sleep(1500L);
			last = this.adminClient.inspect(baseUrl);
		}
		return last;
	}

	/** 安装后等待 exe 出现在常见路径。 */
	private String waitForExe(Duration limit) throws InterruptedException {
		long deadline = System.nanoTime() + limit.toNanos();
		String exe = resolveExe();
		while (exe == null && System.nanoTime() < deadline) {
			Thread.sleep(1500L);
			this.softwareCatalog.refresh();
			exe = resolveExe();
		}
		return exe;
	}

	/** 优先应用 deps/ollama，其次软件目录扫描结果。 */
	private String resolveExe() {
		String managed = this.managedDeps.ollamaExe();
		if (isRealOllamaExe(managed)) {
			return managed;
		}
		String fromCatalog = this.softwareCatalog.ollamaExe();
		return isRealOllamaExe(fromCatalog) ? fromCatalog : null;
	}

	/** 刷新空闲状态快照。 */
	private OllamaInstallStatus refreshIdleStatus() {
		String exe = resolveExe();
		OllamaInstallStatus idle = OllamaInstallStatus.idle(exe != null, blankToEmpty(exe));
		this.status.set(idle);
		return idle;
	}

	/** 发布进度（供轮询）。 */
	private void publish(String phase, String message, boolean busy, boolean installed, String exePath) {
		this.status.set(new OllamaInstallStatus(phase, message, busy, installed, blankToEmpty(exePath)));
	}

	/** 排除误把本应用当成 ollama.exe 的路径。 */
	private static boolean isRealOllamaExe(String path) {
		if (path == null || path.isBlank()) {
			return false;
		}
		Path file = Path.of(path);
		if (!Files.isRegularFile(file)) {
			return false;
		}
		String lower = path.toLowerCase(Locale.ROOT);
		if (lower.contains("ollamachat")) {
			return false;
		}
		String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
		return "ollama.exe".equals(name);
	}

	/** 读取进程输出的前若干字符，避免阻塞占满内存。 */
	private static String readLimited(InputStream in, int limit) throws IOException {
		byte[] buffer = in.readNBytes(Math.max(1, limit));
		return new String(buffer);
	}

	private static boolean isWindows() {
		String os = System.getProperty("os.name", "");
		return os.toLowerCase(Locale.ROOT).contains("win");
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
