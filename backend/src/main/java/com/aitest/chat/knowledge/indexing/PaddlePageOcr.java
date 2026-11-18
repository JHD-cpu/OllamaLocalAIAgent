package com.aitest.chat.knowledge.indexing;


import com.aitest.chat.knowledge.config.RagProperties;
import com.aitest.chat.desktop.PythonExecutableLookup;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** 通过长驻 Python 进程调用 PaddleOCR 识别页面。 */
@Component
public class PaddlePageOcr implements PdfTextExtractor.PageOcr, DisposableBean, PaddleAvailability {
   private static final Logger log = LoggerFactory.getLogger(PaddlePageOcr.class);
   private final RagProperties properties;
   private final PythonExecutableLookup softwareCatalog;
   private final Object lock = new Object();
   private Process process;
   private BufferedWriter writer;
   private BufferedReader reader;
   private final StringBuilder errors = new StringBuilder();
   private final AtomicReference<String> unavailable = new AtomicReference<>();

   /** 注入 RAG 配置与本机 Python 查找。 */
   PaddlePageOcr(RagProperties properties, PythonExecutableLookup softwareCatalog) {
      this.properties = properties;
      this.softwareCatalog = softwareCatalog;
   }

   /** 将页面图像写入临时文件并请求 OCR 文本。 */
   @Override
   public String read(BufferedImage image) {
      Path imageFile = null;
      long started = System.nanoTime();

      String var9;
      try {
         this.ensureAvailable();
         imageFile = Files.createTempFile("paddle-ocr-", ".png");
         ImageIO.write(image, "png", imageFile.toFile());
         log.info("PaddleOCR 开始 {}x{}", image.getWidth(), image.getHeight());
         synchronized (this.lock) {
            this.start();
            this.writer.write(imageFile.toString());
            this.writer.newLine();
            this.writer.flush();
            StringBuilder text = new StringBuilder();

            String line;
            while ((line = this.reader.readLine()) != null && !"---END---".equals(line)) {
               if (line.startsWith("---ERROR---")) {
                  throw new IllegalStateException(this.userFacing(line.substring("---ERROR---".length()).trim()));
               }

               if (text.length() > 0) {
                  text.append('\n');
               }

               text.append(line);
            }

            if (line == null) {
               String detail = this.errors.toString().trim();
               this.stop();
               this.unavailable.set(null);
               throw new IllegalStateException(this.userFacing(detail.isEmpty() ? "PaddleOCR 进程已退出。" : detail));
            }

            String recognized = text.toString().trim();
            log.info("PaddleOCR 完成 chars={} 耗时={}ms", recognized.length(), (System.nanoTime() - started) / 1000000L);
            var9 = recognized;
         }
      } catch (IOException var21) {
         this.stop();
         this.unavailable.set(null);
         throw new IllegalStateException(this.userFacing(var21.getMessage()), var21);
      } finally {
         if (imageFile != null) {
            try {
               Files.deleteIfExists(imageFile);
            } catch (IOException var19) {
               log.warn("删除临时图片失败 {}", imageFile);
            }
         }
      }

      return var9;
   }

   /** 清除不可用缓存，安装依赖后可重新探测。 */
   @Override
   public void resetAvailability() {
      synchronized (this.lock) {
         this.stop();
         this.unavailable.set(null);
      }
   }

   /** 返回当前 OCR 环境探测结果（不抛异常）。 */
   @Override
   public String probeReason() {
      return this.probe();
   }

   /** 探测环境；不可用时缓存原因并抛错。 */
   private void ensureAvailable() {
      String cached = this.unavailable.get();
      if (cached != null) {
         if (!cached.isEmpty()) {
            throw new IllegalStateException(cached);
         }
      } else {
         synchronized (this.lock) {
            cached = this.unavailable.get();
            if (cached != null) {
               if (!cached.isEmpty()) {
                  throw new IllegalStateException(cached);
               }
            } else {
               String reason = this.probe();
               this.unavailable.set(reason == null ? "" : reason);
               if (reason != null) {
                  log.warn("PaddleOCR 不可用：{}", reason);
                  throw new IllegalStateException(reason);
               }
            }
         }
      }
   }

   /** 检测脚本、Python 与 paddleocr 模块；失败返回说明文案。 */
   private String probe() {
      Path script = this.script();
      if (!Files.isRegularFile(script)) {
         return this.missing("找不到 PaddleOCR 脚本：" + script.toAbsolutePath());
      } else {
         String python = this.pythonCommand();
         ProcessBuilder builder = new ProcessBuilder(
            python,
            "-c",
            "import importlib.util,os,sys; t=os.environ.get('PIP_TARGET'); t and t not in sys.path and sys.path.insert(0,t); print('ok' if importlib.util.find_spec('paddleocr') else 'missing')"
         );
         builder.redirectErrorStream(true);
         builder.environment().put("PYTHONIOENCODING", "utf-8");
         String pipTarget = System.getenv("PIP_TARGET");
         if (pipTarget != null && !pipTarget.isBlank()) {
            builder.environment().put("PIP_TARGET", pipTarget);
         }

         try {
            Process probe = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8));

            String output;
            try {
               output = reader.lines().reduce((a, b) -> b).orElse("").trim();
            } catch (Throwable var11) {
               try {
                  reader.close();
               } catch (Throwable var10) {
                  var11.addSuppressed(var10);
               }

               throw var11;
            }

            reader.close();
            boolean finished = probe.waitFor(20L, TimeUnit.SECONDS);
            if (!finished) {
               probe.destroyForcibly();
               return this.missing("检测 PaddleOCR 超时，请确认 Python 命令可用：" + python);
            } else if (probe.exitValue() != 0) {
               return this.missing("无法运行 Python（" + python + "）。请先安装 Python，并确认可在命令行执行该命令。");
            } else if (!"missing".equalsIgnoreCase(output) && !output.contains("missing")) {
               return !output.toLowerCase().contains("ok") ? this.missing("检测 PaddleOCR 失败：" + brief(output)) : null;
            } else {
               return this.missing("已找到 Python（" + python + "），但未安装 PaddleOCR。请执行：pip install paddleocr");
            }
         } catch (IOException var12) {
            return this.missing("未找到 Python（配置为 " + python + "）。扫描版 PDF 需要本机 Python 和 PaddleOCR，请先安装。");
         } catch (InterruptedException var13) {
            Thread.currentThread().interrupt();
            return this.missing("检测 PaddleOCR 被中断。");
         }
      }
   }

   /** 启动 OCR 子进程并接通 stdin/stdout。 */
   private void start() throws IOException {
      if (this.process == null || !this.process.isAlive()) {
         Path script = this.script();
         if (!Files.isRegularFile(script)) {
            throw new IllegalStateException(this.missing("找不到 PaddleOCR 脚本：" + script.toAbsolutePath()));
         } else {
            String python = this.pythonCommand();
            this.errors.setLength(0);
            ProcessBuilder builder = new ProcessBuilder(python, "-u", script.toString());
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            builder.environment().put("PYTHONUNBUFFERED", "1");
            builder.environment().put("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True");
            builder.environment().put("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "False");
            builder.environment().put("FLAGS_use_mkldnn", "0");

            try {
               this.process = builder.start();
            } catch (IOException var5) {
               throw new IOException(this.missing("未找到 Python（配置为 " + python + "）。扫描版 PDF 需要本机 Python 和 PaddleOCR，请先安装。"), var5);
            }

            log.info("启动 PaddleOCR python={} script={}", python, script);
            this.writer = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
            Thread drain = new Thread(() -> this.drain(this.process), "paddle-ocr-err");
            drain.setDaemon(true);
            drain.start();
         }
      }
   }

   /** 后台读取 stderr 供故障诊断。 */
   private void drain(Process current) {
      String line;
      try (BufferedReader errors = new BufferedReader(new InputStreamReader(current.getErrorStream(), StandardCharsets.UTF_8))) {
         while ((line = errors.readLine()) != null) {
            synchronized (this.errors) {
               if (this.errors.length() < 4000) {
                  this.errors.append(line).append('\n');
               }
            }
         }
      } catch (IOException var9) {
         log.warn("读取 PaddleOCR 日志失败");
      }
   }

   /** 解析 PaddleOCR 脚本的实际路径。 */
   private Path script() {
      String configured = this.properties.getPaddleScript();
      Path given = Path.of(configured != null && !configured.isBlank() ? configured : "scripts/paddle_ocr.py");
      if (given.isAbsolute()) {
         return given;
      } else {
         Path bundled = bundledScript(given);
         if (bundled != null) {
            return bundled;
         } else {
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
      }
   }

   /** 在 JAR 旁查找打包脚本。 */
   private static Path bundledScript(Path given) {
      try {
         CodeSource source = PaddlePageOcr.class.getProtectionDomain().getCodeSource();
         if (source != null && source.getLocation() != null) {
            Path location = Path.of(source.getLocation().toURI());
            if (!Files.isRegularFile(location)) {
               return null;
            } else {
               Path bundled = location.getParent().resolve(given);
               return Files.isRegularFile(bundled) ? bundled : null;
            }
         } else {
            return null;
         }
      } catch (URISyntaxException | RuntimeException var4) {
         return null;
      }
   }

   /** 解析用于启动脚本的 Python 命令。 */
   private String pythonCommand() {
      String configured = this.properties.getPaddlePython();
      String value = configured == null ? "" : configured.trim();
      if (value.isEmpty() || "python".equalsIgnoreCase(value) || "python3".equalsIgnoreCase(value)) {
         String resolved = this.softwareCatalog.pythonExe();
         if (resolved != null && !resolved.isBlank()) {
            return resolved;
         }
      }

      return value.isEmpty() ? "python" : value;
   }

   /** 组装「未安装 PaddleOCR」类提示。 */
   private String missing(String detail) {
      return "未安装或无法使用 PaddleOCR。" + detail + " 扫描版 PDF 需要本机 Python，并执行 pip install paddleocr 后再上传。";
   }

   /** 将底层错误转为上传场景可读文案。 */
   private String userFacing(String detail) {
      String text = detail == null ? "" : detail.trim();
      String lower = text.toLowerCase();
      if (!lower.contains("paddleocr") || !lower.contains("no module") && !lower.contains("modulenotfound") && !lower.contains("not found")) {
         if (text.startsWith("未安装或无法使用 PaddleOCR")) {
            return text;
         } else {
            return text.isEmpty() ? this.missing("识别过程失败。") : "PaddleOCR 识别失败：" + brief(text) + " 若本机未安装，请先安装 Python 并执行 pip install paddleocr。";
         }
      } else {
         return this.missing("Python 中缺少 paddleocr 模块。");
      }
   }

   /** 截断单行错误详情。 */
   private static String brief(String value) {
      String trimmed = value.replace('\n', ' ').trim();
      return trimmed.length() > 300 ? trimmed.substring(0, 300) : trimmed;
   }

   /** 终止 OCR 子进程并清空句柄。 */
   private void stop() {
      Process current = this.process;
      this.process = null;
      this.writer = null;
      this.reader = null;
      if (current != null) {
         current.destroy();

         try {
            current.waitFor(2L, TimeUnit.SECONDS);
         } catch (InterruptedException var3) {
            Thread.currentThread().interrupt();
         }

         current.destroyForcibly();
      }
   }

   /** 容器关闭时停止 OCR 进程。 */
   public void destroy() {
      synchronized (this.lock) {
         this.stop();
      }
   }
}
