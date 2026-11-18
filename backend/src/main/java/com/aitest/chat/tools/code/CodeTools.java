package com.aitest.chat.tools.code;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 在工作区内读写文件并执行脚本/命令的 Spring AI 工具集。 */
public class CodeTools {
   private static final Logger log = LoggerFactory.getLogger(CodeTools.class);
   private static final int MAX_TEXT = 100000;
   private static final int MAX_OUTPUT = 8000;
   private static final int MAX_COMMAND = 2000;
   private final Path workspace;
   private final int timeoutSeconds;

   /** 绑定已规范化的工作区根目录与命令超时秒数。 */
   public CodeTools(Path workspace, int timeoutSeconds) {
      this.workspace = workspace.toAbsolutePath().normalize();
      this.timeoutSeconds = timeoutSeconds;
   }

   /** 从配置字符串解析工作区路径。 */
   public CodeTools(String configured, int timeoutSeconds) {
      this(open(configured), timeoutSeconds);
   }

   /** 校验并创建代码工作区目录。 */
   private static Path open(String configured) {
      if (configured != null && !configured.isBlank()) {
         Path path = Path.of(configured.trim());
         if (!path.isAbsolute()) {
            path = Path.of(System.getProperty("user.dir")).resolve(path);
         }

         path = path.toAbsolutePath().normalize();
         if (path.getNameCount() == 0) {
            throw new IllegalArgumentException("代码工作区不能是磁盘根目录。");
         } else {
            try {
               Files.createDirectories(path);
               return path;
            } catch (IOException var3) {
               throw new IllegalArgumentException("无法创建代码工作区：" + var3.getMessage());
            }
         }
      } else {
         throw new IllegalArgumentException("代码工作区不能为空。");
      }
   }

   /** 新建文件，已存在则报错不覆盖。 */
   @Tool(
      name = "create_file",
      description = "在工作区新建文件并写入内容。文件已存在时失败，不会覆盖。适合第一次生成代码。"
   )
   public String createFile(@ToolParam(description = "相对工作区的文件路径",required = true) String path, @ToolParam(description = "文件全文",required = true) String content) {
      try {
         Path file = this.resolve(path);
         if (Files.exists(file)) {
            return "文件已存在，没有覆盖：" + this.display(file);
         } else {
            this.write(file, content);
            log.info("创建文件 {}", this.display(file));
            return "已创建 " + this.display(file);
         }
      } catch (IOException | RuntimeException var4) {
         return failure(var4);
      }
   }

   /** 写入或覆盖工作区内的文件。 */
   @Tool(
      name = "write_file",
      description = "把工作区里的文件写成给定内容。文件不存在时会创建，已存在时覆盖。"
   )
   public String writeFile(@ToolParam(description = "相对工作区的文件路径",required = true) String path, @ToolParam(description = "文件全文",required = true) String content) {
      try {
         Path file = this.resolve(path);
         this.write(file, content);
         log.info("写入文件 {}", this.display(file));
         return "已写入 " + this.display(file);
      } catch (IOException | RuntimeException var4) {
         return failure(var4);
      }
   }

   /** 读文本文件或列出目录下文件名（有长度上限）。 */
   @Tool(
      name = "read_file",
      description = "读取工作区里的文本文件。路径是目录时列出其中的文件名。修改已有代码前先读取。"
   )
   public String readFile(@ToolParam(description = "相对工作区的文件或目录路径",required = true) String path) {
      try {
         Path file = this.resolve(path);
         if (!Files.exists(file)) {
            return "找不到：" + this.display(file);
         } else if (Files.isDirectory(file)) {
            return this.listDirectory(file);
         } else {
            String text = Files.readString(file);
            log.info("读取文件 {} 字符={}", this.display(file), text.length());
            if (text.length() > 100000) {
               return text.substring(0, 100000) + "\n…文件过长，已截断";
            } else {
               return text.isEmpty() ? "文件是空的。" : text;
            }
         }
      } catch (IOException | RuntimeException var4) {
         return failure(var4);
      }
   }

   /** 按扩展名选择解释器并运行工作区内文件。 */
   @Tool(
      name = "run_file",
      description = "运行工作区里的文件。py 用 python，js 用 node，cmd、bat、ps1 用系统解释器，其它文件直接执行。"
   )
   public String runFile(@ToolParam(description = "相对工作区的文件路径",required = true) String path) {
      try {
         Path file = this.resolve(path);
         if (!Files.isRegularFile(file)) {
            return "找不到可运行的文件：" + this.display(file);
         } else {
            log.info("运行文件 {}", this.display(file));
            return this.run(this.commandFor(file));
         }
      } catch (RuntimeException var3) {
         return failure(var3);
      }
   }

   /** 在 Windows 上经 cmd 执行单条命令（含 start 打开程序）。 */
   @Tool(
      name = "run_cmd",
      description = "在这台 Windows 电脑上执行一条 cmd 命令。打开网站请用 open_browser，打开本机程序请优先用 open_app；本工具用于检查代码或其它命令。"
   )
   public String runCmd(@ToolParam(description = "cmd 命令，例如 dir 或 python -V", required = true) String command) {
      String text = command == null ? "" : command.trim();
      if (!text.isEmpty() && text.length() <= 2000) {
         log.info("执行命令 {}", text.length() > 120 ? text.substring(0, 120) + "…" : text);
         List<String> shell = windows() ? List.of("cmd.exe", "/c", "chcp 65001>nul & " + text) : List.of("sh", "-c", text);
         return this.run(shell);
      } else {
         return "命令不能为空，且不能超过 2000 个字符。";
      }
   }

   /** 校验大小与路径后写入 UTF-8 文本。 */
   private void write(Path file, String content) throws IOException {
      String text = content == null ? "" : content;
      if (text.length() > 100000) {
         throw new IllegalArgumentException("文件内容不能超过 100000 个字符。");
      } else {
         this.ensureInside(file);
         Files.createDirectories(file.getParent());
         Files.writeString(file, text, StandardCharsets.UTF_8);
      }
   }

   /** 列出目录下最多 100 个条目名。 */
   private String listDirectory(Path directory) throws IOException {
      List<String> names = new ArrayList<>();

      try (Stream<Path> entries = Files.list(directory)) {
         entries.limit(100L).forEach(entry -> names.add(entry.getFileName().toString()));
      }

      log.info("列出目录 {} 共{}项", this.display(directory), names.size());
      return names.isEmpty() ? this.display(directory) + " 是空目录。" : this.display(directory) + "\n" + String.join("\n", names);
   }

   /** 根据扩展名构造进程启动参数列表。 */
   private List<String> commandFor(Path file) {
      String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
      String location = file.toString();
      if (name.endsWith(".py")) {
         return List.of("python", location);
      } else if (name.endsWith(".js") || name.endsWith(".mjs")) {
         return List.of("node", location);
      } else if (name.endsWith(".ps1")) {
         return List.of("powershell", "-NoProfile", "-File", location);
      } else {
         return !windows() || !name.endsWith(".cmd") && !name.endsWith(".bat") ? List.of(location) : List.of("cmd.exe", "/c", location);
      }
   }

   /** 在工作区目录启动子进程并收集截断后的标准输出。 */
   private String run(List<String> command) {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.directory(this.workspace.toFile());
      builder.redirectErrorStream(true);
      builder.environment().put("PYTHONIOENCODING", "utf-8");

      try {
         Process process = builder.start();
         AtomicReference<String> output = new AtomicReference<>("");
         Thread pump = Thread.startVirtualThread(() -> output.set(readLimited(process.getInputStream())));
         boolean finished = process.waitFor(this.timeoutSeconds, TimeUnit.SECONDS);
         if (!finished) {
            process.destroyForcibly();
         }

         pump.join(1000L);
         String text = output.get() == null ? "" : output.get();
         return !finished ? "执行超时（" + this.timeoutSeconds + " 秒）。\n" + text : "退出码 " + process.exitValue() + "\n" + text;
      } catch (IOException var8) {
         return "执行失败：" + var8.getMessage();
      } catch (InterruptedException var9) {
         Thread.currentThread().interrupt();
         return "执行被中断。";
      }
   }

   /** 最多读取固定字节数的进程输出。 */
   private static String readLimited(InputStream input) {
      try {
         byte[] data = input.readNBytes(8000);
         boolean more = input.read() >= 0;
         String text = new String(data, StandardCharsets.UTF_8).replace("\u0000", "");
         return more ? text + "\n…输出已截断" : text;
      } catch (IOException var4) {
         return "读取输出失败：" + var4.getMessage();
      }
   }

   /** 将相对路径解析到工作区内并规范化。 */
   Path resolve(String raw) {
      if (raw != null && !raw.isBlank()) {
         Path target = Path.of(raw.trim());
         if (!target.isAbsolute()) {
            target = this.workspace.resolve(target);
         }

         target = target.normalize();
         if (!target.startsWith(this.workspace)) {
            throw new IllegalArgumentException("只能访问工作区内的文件。");
         } else {
            return target;
         }
      } else {
         throw new IllegalArgumentException("路径不能为空。");
      }
   }

   /** 用真实路径确认目标仍在工作区之内（防符号链接逃逸）。 */
   private void ensureInside(Path file) throws IOException {
      Path cursor = file;

      while (cursor != null && !Files.exists(cursor)) {
         cursor = cursor.getParent();
      }

      if (cursor == null) {
         throw new IllegalArgumentException("只能访问工作区内的文件。");
      } else {
         Path real = cursor.toRealPath();
         Path root = this.workspace.toRealPath();
         if (!real.startsWith(root)) {
            throw new IllegalArgumentException("只能访问工作区内的文件。");
         }
      }
   }

   /** 返回相对工作区、斜杠统一的路径显示串。 */
   private String display(Path file) {
      return this.workspace.relativize(file).toString().replace('\\', '/');
   }

   /** 当前 JVM 是否运行在 Windows 上。 */
   private static boolean windows() {
      return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
   }

   /** 将异常消息返回给模型，无消息时用通用失败文案。 */
   private static String failure(Exception ex) {
      return ex.getMessage() == null ? "操作失败" : ex.getMessage();
   }
}
