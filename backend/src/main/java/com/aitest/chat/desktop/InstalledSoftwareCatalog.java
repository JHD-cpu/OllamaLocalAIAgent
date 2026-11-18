package com.aitest.chat.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import com.aitest.chat.desktop.deps.ManagedDepsLayout;

import tools.jackson.databind.json.JsonMapper;

/** 启动时扫描本机软件，持久化并供提示词与 Python 路径查询。 */
@Component
public class InstalledSoftwareCatalog implements PythonExecutableLookup, ApplicationListener<ApplicationReadyEvent> {
   private static final Logger log = LoggerFactory.getLogger(InstalledSoftwareCatalog.class);
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
   private final JsonMapper jsonMapper;
   private final Path softwareFile;
   private final ManagedDepsLayout managedDeps;
   private final Object lock = new Object();
   private InstalledSoftwareSnapshot snapshot = InstalledSoftwareSnapshot.empty("");

   /** 解析配置文件路径并准备 JSON 读写。 */
   public InstalledSoftwareCatalog(JsonMapper jsonMapper, @Value("${app.software-file:data/installed-software.json}") String softwareFile,
         ManagedDepsLayout managedDeps) {
      this.jsonMapper = jsonMapper;
      this.softwareFile = resolve(softwareFile);
      this.managedDeps = managedDeps;
   }

   /** 应用就绪后触发首次扫描。 */
   public void onApplicationEvent(ApplicationReadyEvent event) {
      this.refresh();
   }

   /** 重新扫描注册表，失败时尽量回退到磁盘上的旧快照。 */
   public void refresh() {
      synchronized (this.lock) {
         try {
            List<InstalledSoftwareItem> items = InstalledSoftwareScanner.scan();
            Map<String, String> resolved = preferManaged(InstalledSoftwareScanner.resolve(items));
            InstalledSoftwareSnapshot fresh = new InstalledSoftwareSnapshot(LocalDateTime.now().format(TIME), items, resolved);
            this.persist(fresh);
            this.snapshot = fresh;
            log.info(
               "已扫描已装软件 {} 条，写入 {}；python={} chrome={} node={} ollama={}",
               new Object[]{
                  items.size(),
                  this.softwareFile,
                  resolved.getOrDefault("python", "-"),
                  resolved.getOrDefault("chrome", "-"),
                  resolved.getOrDefault("node", "-"),
                  resolved.getOrDefault("ollama", "-")
               }
            );
         } catch (RuntimeException var6) {
            log.warn("扫描已装软件失败：{}", var6.getMessage());
            InstalledSoftwareSnapshot loaded = this.loadQuietly();
            if (loaded != null) {
               this.snapshot = loaded;
               log.warn("改用已有软件配置 {} 条", loaded.items().size());
            }
         }
      }
   }

   /** 返回当前内存中的软件快照副本引用（调用方只读使用）。 */
   public InstalledSoftwareSnapshot snapshot() {
      synchronized (this.lock) {
         return this.snapshot;
      }
   }

   /** {@inheritDoc} 优先使用应用 deps 目录中的 Python。 */
   public String pythonExe() {
      return firstNonBlank(this.managedDeps.pythonExe(), this.resolved("python"));
   }

   /** 已解析的 Chrome（系统安装，仅作参考；打开网页用默认浏览器）。 */
   public String chromeExe() {
      return this.resolved("chrome");
   }

   /** 已解析的 Node.js；优先应用 deps 目录。 */
   public String nodeExe() {
      return firstNonBlank(this.managedDeps.nodeExe(), this.resolved("node"));
   }

   /** 已解析的 Ollama；优先应用 deps 目录。 */
   public String ollamaExe() {
      return firstNonBlank(this.managedDeps.ollamaExe(), this.resolved("ollama"));
   }

   /** 把应用自管 deps 路径覆盖进 resolved 映射。 */
   private Map<String, String> preferManaged(Map<String, String> scanned) {
      LinkedHashMap<String, String> merged = new LinkedHashMap<>(scanned == null ? Map.of() : scanned);
      putIfPresent(merged, "python", this.managedDeps.pythonExe());
      putIfPresent(merged, "node", this.managedDeps.nodeExe());
      putIfPresent(merged, "ollama", this.managedDeps.ollamaExe());
      return Map.copyOf(merged);
   }

   private static void putIfPresent(Map<String, String> map, String key, String value) {
      if (value != null && !value.isBlank()) {
         map.put(key, value);
      }
   }

   private static String firstNonBlank(String first, String second) {
      if (first != null && !first.isBlank()) {
         return first;
      }
      return second != null && !second.isBlank() ? second : null;
   }

   /**
    * 按名称模糊匹配已装软件，返回可启动路径（exe 优先，其次安装目录下常见 exe）。
    * 找不到时返回 null，并由调用方用 {@link #suggestNames(String)} 提示候选。
    */
   public String findLaunchPath(String query) {
      String needle = query == null ? "" : query.trim().toLowerCase();
      if (needle.isEmpty()) {
         return null;
      }
      // 常见别名
      if ("notepad".equals(needle) || "记事本".equals(needle)) {
         return "notepad.exe";
      }
      if ("explorer".equals(needle) || "资源管理器".equals(needle)) {
         return "explorer.exe";
      }
      if ("cmd".equals(needle) || "命令提示符".equals(needle)) {
         return "cmd.exe";
      }
      if ("powershell".equals(needle)) {
         return "powershell.exe";
      }
      InstalledSoftwareSnapshot current = this.snapshot();
      Map<String, String> resolved = current.resolved() == null ? Map.of() : current.resolved();
      for (Map.Entry<String, String> entry : resolved.entrySet()) {
         if (entry.getKey() != null && entry.getKey().toLowerCase().contains(needle) && !blank(entry.getValue())) {
            return entry.getValue();
         }
      }
      List<InstalledSoftwareItem> items = current.items() == null ? List.of() : current.items();
      InstalledSoftwareItem best = null;
      for (InstalledSoftwareItem item : items) {
         String name = item.name() == null ? "" : item.name().toLowerCase();
         if (!name.contains(needle)) {
            continue;
         }
         if (best == null || name.length() < best.name().length()) {
            best = item;
         }
      }
      if (best == null) {
         return null;
      }
      if (!blank(best.exePath())) {
         return best.exePath();
      }
      if (!blank(best.installLocation())) {
         return best.installLocation();
      }
      return null;
   }

   /** 返回名称包含关键词的候选软件名（最多 8 个）。 */
   public List<String> suggestNames(String query) {
      String needle = query == null ? "" : query.trim().toLowerCase();
      List<String> names = new ArrayList<>();
      if (needle.isEmpty()) {
         return names;
      }
      for (InstalledSoftwareItem item : this.snapshot().items() == null ? List.<InstalledSoftwareItem>of() : this.snapshot().items()) {
         String name = item.name() == null ? "" : item.name();
         if (name.toLowerCase().contains(needle)) {
            names.add(name);
            if (names.size() >= 8) {
               break;
            }
         }
      }
      return names;
   }

   /** 生成注入系统提示词的本机软件摘要（控制条目数量）。 */
   public String promptSummary() {
      InstalledSoftwareSnapshot current = this.snapshot();
      StringBuilder text = new StringBuilder();
      Map<String, String> resolved = current.resolved() == null ? Map.of() : current.resolved();
      if (!resolved.isEmpty()) {
         text.append("本机已解析到的程序：");
         List<String> parts = new ArrayList<>();
         resolved.forEach((key, pathx) -> parts.add(key + "=" + pathx));
         text.append(String.join("；", parts)).append("。");
      }

      List<InstalledSoftwareItem> items = current.items() == null ? List.of() : current.items();
      if (items.isEmpty()) {
         return text.toString();
      } else {
         if (!text.isEmpty()) {
            text.append('\n');
         }

         text.append("本机已装软件（部分）：");
         int limit = Math.min(80, items.size());

         for (int i = 0; i < limit; i++) {
            InstalledSoftwareItem item = items.get(i);
            text.append('\n').append("- ").append(item.name());
            String path = !blank(item.exePath()) ? item.exePath() : item.installLocation();
            if (!blank(path)) {
               text.append(" → ").append(path);
            }
         }

         if (items.size() > limit) {
            text.append("\n- …共 ").append(items.size()).append(" 条，完整列表见配置文件");
         }

         return text.toString();
      }
   }

   /** 从快照的 resolved 映射中取非空路径。 */
   private String resolved(String key) {
      Map<String, String> map = this.snapshot().resolved();
      if (map == null) {
         return null;
      } else {
         String value = map.get(key);
         return blank(value) ? null : value;
      }
   }

   /** 将快照以漂亮 JSON 写入配置文件。 */
   private void persist(InstalledSoftwareSnapshot snapshot) {
      try {
         Path parent = this.softwareFile.getParent();
         if (parent != null) {
            Files.createDirectories(parent);
         }

         this.jsonMapper.writerWithDefaultPrettyPrinter().writeValue(this.softwareFile.toFile(), snapshot);
      } catch (IOException var3) {
         throw new IllegalStateException("无法写入软件配置 " + this.softwareFile + "：" + var3.getMessage(), var3);
      }
   }

   /** 静默读取磁盘快照，失败返回 null。 */
   private InstalledSoftwareSnapshot loadQuietly() {
      if (!Files.isRegularFile(this.softwareFile)) {
         return null;
      } else {
         try {
            return (InstalledSoftwareSnapshot)this.jsonMapper.readValue(this.softwareFile.toFile(), InstalledSoftwareSnapshot.class);
         } catch (RuntimeException var2) {
            log.warn("读取软件配置失败：{}", var2.getMessage());
            return null;
         }
      }
   }

   /** 将配置路径解析为绝对路径。 */
   private static Path resolve(String configured) {
      Path path = Path.of(configured != null && !configured.isBlank() ? configured.trim() : "data/installed-software.json");
      return path.isAbsolute() ? path.normalize() : Path.of(System.getProperty("user.dir")).resolve(path).normalize();
   }

   /** 判断字符串是否为 null 或空白。 */
   private static boolean blank(String value) {
      return value == null || value.isBlank();
   }
}
