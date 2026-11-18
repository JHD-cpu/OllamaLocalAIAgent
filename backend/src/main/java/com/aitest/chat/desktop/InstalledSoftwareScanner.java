package com.aitest.chat.desktop;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 从 Windows 注册表扫描已装软件并解析常用开发工具路径。 */
public final class InstalledSoftwareScanner {
   private static final Logger log = LoggerFactory.getLogger(InstalledSoftwareScanner.class);
   private static final String[] UNINSTALL_PATHS = new String[]{
      "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall", "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
   };
   private static final String APP_PATHS = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths";
   private static final String[] APP_EXES = new String[]{"chrome.exe", "python.exe", "python3.exe", "node.exe", "ollama.exe"};

   /** 工具类，禁止实例化。 */
   private InstalledSoftwareScanner() {
   }

   /** 当前 JVM 是否运行在 Windows 上。 */
   static boolean windows() {
      String os = System.getProperty("os.name", "");
      return os.toLowerCase(Locale.ROOT).contains("win");
   }

   /** 枚举卸载项与 App Paths，合并为去重后的软件列表。 */
   static List<InstalledSoftwareItem> scan() {
      if (!windows()) {
         return List.of();
      } else {
         Map<String, InstalledSoftwareItem> byName = new LinkedHashMap<>();

         for (String path : UNINSTALL_PATHS) {
            readUninstall(WinReg.HKEY_LOCAL_MACHINE, path, byName);
            readUninstall(WinReg.HKEY_CURRENT_USER, path, byName);
         }

         for (String exe : APP_EXES) {
            String full = appPath(WinReg.HKEY_LOCAL_MACHINE, exe);
            if (full == null) {
               full = appPath(WinReg.HKEY_CURRENT_USER, exe);
            }

            if (full != null && !isInstallerPath(full) && Files.isRegularFile(Path.of(full))) {
               Path file = Path.of(full);
               if (file.getFileName().toString().equalsIgnoreCase(exe)) {
                  String name = displayNameForExe(exe);
                  InstalledSoftwareItem existing = byName.get(name.toLowerCase(Locale.ROOT));
                  if (existing == null) {
                     byName.put(name.toLowerCase(Locale.ROOT), new InstalledSoftwareItem(name, "", parentOrEmpty(file), full));
                  } else if (blank(existing.exePath()) || isInstallerPath(existing.exePath())) {
                     byName.put(
                        name.toLowerCase(Locale.ROOT), new InstalledSoftwareItem(existing.name(), existing.publisher(), existing.installLocation(), full)
                     );
                  }
               }
            }
         }

         return List.copyOf(byName.values());
      }
   }

   /** 在扫描结果与常见安装目录中定位 chrome/python/node/ollama。 */
   static Map<String, String> resolve(List<InstalledSoftwareItem> items) {
      Map<String, String> resolved = new LinkedHashMap<>();
      putResolved(
         resolved,
         "chrome",
         firstPresent(
            findExe(items, "chrome.exe", "google chrome"),
            knownFile(Path.of(System.getenv().getOrDefault("PROGRAMFILES", "C:\\Program Files"), "Google", "Chrome", "Application", "chrome.exe"))
         )
      );
      putResolved(
         resolved,
         "python",
         firstPresent(
            findExe(items, "python.exe", "python 3", "python3"),
            findExe(items, "python3.exe", "python 3", "python3"),
            findUnder(Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Programs", "Python"), "python.exe")
         )
      );
      putResolved(
         resolved,
         "node",
         firstPresent(
            findExe(items, "node.exe", "node.js"), knownFile(Path.of(System.getenv().getOrDefault("PROGRAMFILES", "C:\\Program Files"), "nodejs", "node.exe"))
         )
      );
      putResolved(
         resolved,
         "ollama",
         firstPresent(
            findExe(items, "ollama.exe", "ollama"), knownFile(Path.of(System.getenv().getOrDefault("LOCALAPPDATA", ""), "Programs", "Ollama", "ollama.exe"))
         )
      );
      return Map.copyOf(resolved);
   }

   /** 若路径为存在的非安装包 exe 则返回绝对路径。 */
   private static String knownFile(Path path) {
      return path != null && Files.isRegularFile(path) && !isInstallerPath(path.toString()) ? path.toAbsolutePath().normalize().toString() : null;
   }

   /** 在目录下有限深度搜索指定 exe 文件名。 */
   private static String findUnder(Path root, String exeName) {
      if (root != null && Files.isDirectory(root)) {
         try {
            String var3;
            try (Stream<Path> stream = Files.walk(root, 3)) {
               var3 = stream.filter(x$0 -> Files.isRegularFile(x$0))
                  .filter(path -> path.getFileName().toString().equalsIgnoreCase(exeName))
                  .map(path -> path.toAbsolutePath().normalize().toString())
                  .filter(path -> !isInstallerPath(path))
                  .filter(path -> !path.toLowerCase(Locale.ROOT).contains("\\venv\\"))
                  .findFirst()
                  .orElse(null);
            }

            return var3;
         } catch (Exception var7) {
            return null;
         }
      } else {
         return null;
      }
   }

   /** 向 resolved 映射写入非空路径。 */
   private static void putResolved(Map<String, String> resolved, String key, String path) {
      if (!blank(path)) {
         resolved.put(key, path);
      }
   }

   /** 返回参数中第一个非空字符串。 */
   private static String firstPresent(String... values) {
      for (String value : values) {
         if (!blank(value)) {
            return value;
         }
      }

      return null;
   }

   /** 按 exe 后缀或显示名关键字在列表中定位可执行文件。 */
   private static String findExe(List<InstalledSoftwareItem> items, String exeName, String... nameHints) {
      String want = exeName.toLowerCase(Locale.ROOT);

      for (InstalledSoftwareItem item : items) {
         if (!blank(item.exePath())
            && item.exePath().toLowerCase(Locale.ROOT).endsWith("\\" + want)
            && Files.isRegularFile(Path.of(item.exePath()))
            && !isInstallerPath(item.exePath())) {
            return Path.of(item.exePath()).toAbsolutePath().normalize().toString();
         }
      }

      for (String hint : nameHints) {
         String needle = hint.toLowerCase(Locale.ROOT);

         for (InstalledSoftwareItem itemx : items) {
            if (itemx.name() != null) {
               String name = itemx.name().toLowerCase(Locale.ROOT);
               if (nameContainsHint(name, needle)) {
                  String found = locateExe(itemx, exeName);
                  if (found != null) {
                     return found;
                  }
               }
            }
         }
      }

      return null;
   }

   /** 判断显示名是否匹配搜索提示（Ollama 单独收紧规则）。 */
   private static boolean nameContainsHint(String name, String needle) {
      return !"ollama".equals(needle) ? name.contains(needle) : name.equals("ollama") || name.startsWith("ollama ") || name.endsWith(" ollama");
   }

   /** 从条目的 exe 路径或安装目录中解析目标 exe。 */
   private static String locateExe(InstalledSoftwareItem item, String exeName) {
      String want = exeName.toLowerCase(Locale.ROOT);
      if (!blank(item.exePath())) {
         Path exe = Path.of(item.exePath());
         if (Files.isRegularFile(exe) && exe.getFileName().toString().equalsIgnoreCase(exeName) && !isInstallerPath(exe.toString())) {
            return exe.toAbsolutePath().normalize().toString();
         }
      }

      if (blank(item.installLocation())) {
         return null;
      } else {
         Path dir = Path.of(item.installLocation());
         if (!Files.isDirectory(dir)) {
            return null;
         } else {
            Path direct = dir.resolve(exeName);
            if (Files.isRegularFile(direct) && !isInstallerPath(direct.toString())) {
               return direct.toAbsolutePath().normalize().toString();
            } else {
               try {
                  String var6;
                  try (Stream<Path> stream = Files.walk(dir, 3)) {
                     var6 = stream.filter(x$0 -> Files.isRegularFile(x$0))
                        .filter(path -> path.getFileName().toString().equalsIgnoreCase(exeName))
                        .map(path -> path.toAbsolutePath().normalize().toString())
                        .filter(path -> !isInstallerPath(path))
                        .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(want))
                        .findFirst()
                        .orElse(null);
                  }

                  return var6;
               } catch (Exception var10) {
                  return null;
               }
            }
         }
      }
   }

   /** 过滤安装缓存、卸载器及明显安装包路径。 */
   private static boolean isInstallerPath(String path) {
      String lower = path.toLowerCase(Locale.ROOT);
      return lower.contains("\\package cache\\")
         || lower.contains("\\installer\\")
         || lower.contains("\\uninstall")
         || lower.endsWith("setup.exe")
         || lower.contains("-amd64.exe")
         || lower.contains("-arm64.exe")
         || lower.contains("ollamachat.exe");
   }

   /** 读取 Uninstall 注册表分支并合并到 byName。 */
   private static void readUninstall(HKEY root, String basePath, Map<String, InstalledSoftwareItem> byName) {
      String[] keys;
      try {
         keys = Advapi32Util.registryGetKeys(root, basePath);
      } catch (RuntimeException var17) {
         return;
      }

      for (String key : keys) {
         String sub = basePath + "\\" + key;

         try {
            String name = string(root, sub, "DisplayName");
            if (!blank(name) && !isNoise(name)) {
               String publisher = string(root, sub, "Publisher");
               String location = string(root, sub, "InstallLocation");
               String icon = string(root, sub, "DisplayIcon");
               String exe = exeFromIcon(icon);
               if (blank(location) && !blank(exe)) {
                  location = parentOrEmpty(Path.of(exe));
               }

               String keyName = name.toLowerCase(Locale.ROOT);
               InstalledSoftwareItem existing = byName.get(keyName);
               if (existing == null) {
                  byName.put(keyName, new InstalledSoftwareItem(name.trim(), nullToEmpty(publisher), nullToEmpty(location), nullToEmpty(exe)));
               } else if (blank(existing.exePath()) && !blank(exe)) {
                  byName.put(
                     keyName,
                     new InstalledSoftwareItem(
                        existing.name(), existing.publisher(), blank(existing.installLocation()) ? nullToEmpty(location) : existing.installLocation(), exe
                     )
                  );
               }
            }
         } catch (RuntimeException var16) {
         }
      }
   }

   /** 从 App Paths 读取默认可执行路径。 */
   private static String appPath(HKEY root, String exeName) {
      try {
         String value = Advapi32Util.registryGetStringValue(root, "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exeName, "");
         return cleanPath(value);
      } catch (RuntimeException var3) {
         return null;
      }
   }

   /** 安全读取注册表字符串值。 */
   private static String string(HKEY root, String path, String name) {
      try {
         return !Advapi32Util.registryValueExists(root, path, name) ? null : Advapi32Util.registryGetStringValue(root, path, name);
      } catch (RuntimeException var4) {
         return null;
      }
   }

   /** 跳过 Windows 更新、运行库等无关显示名。 */
   private static boolean isNoise(String name) {
      String lower = name.toLowerCase(Locale.ROOT);
      return lower.contains("update for")
         || lower.contains("security update")
         || lower.startsWith("kb")
         || lower.contains("hotfix")
         || lower.contains("microsoft visual c++") && lower.contains("redistributable");
   }

   /** 从 DisplayIcon 字段解析出 .exe 路径。 */
   private static String exeFromIcon(String icon) {
      if (blank(icon)) {
         return null;
      } else {
         String raw = icon.trim();
         if (raw.startsWith("\"")) {
            int end = raw.indexOf(34, 1);
            if (end > 1) {
               raw = raw.substring(1, end);
            }
         } else {
            int comma = raw.lastIndexOf(44);
            if (comma > 0 && raw.substring(comma + 1).trim().matches("-?\\d+")) {
               raw = raw.substring(0, comma);
            }
         }

         raw = cleanPath(raw);
         if (raw == null) {
            return null;
         } else {
            String lower = raw.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".exe")) {
               return null;
            } else {
               return Files.isRegularFile(Path.of(raw)) ? Path.of(raw).toAbsolutePath().normalize().toString() : raw;
            }
         }
      }
   }

   /** 去掉引号并 trim 路径字符串。 */
   private static String cleanPath(String value) {
      if (blank(value)) {
         return null;
      } else {
         String raw = value.trim();
         if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
            raw = raw.substring(1, raw.length() - 1);
         }

         return raw.isBlank() ? null : raw;
      }
   }

   /** 将常见 exe 文件名映射为友好显示名。 */
   private static String displayNameForExe(String exe) {
      String var1 = exe.toLowerCase(Locale.ROOT);

      return switch (var1) {
         case "chrome.exe" -> "Google Chrome";
         case "python.exe", "python3.exe" -> "Python";
         case "node.exe" -> "Node.js";
         case "ollama.exe" -> "Ollama";
         default -> exe;
      };
   }

   /** 取文件父目录绝对路径，无父级则空串。 */
   private static String parentOrEmpty(Path file) {
      Path parent = file.getParent();
      return parent == null ? "" : parent.toAbsolutePath().normalize().toString();
   }

   /** 判断字符串是否为 null 或空白。 */
   private static boolean blank(String value) {
      return value == null || value.isBlank();
   }

   /** null 转为空串，否则 trim。 */
   private static String nullToEmpty(String value) {
      return value == null ? "" : value.trim();
   }

   /** 测试用：暴露 {@link #resolve} 逻辑。 */
   static Map<String, String> resolveForTest(List<InstalledSoftwareItem> items) {
      return resolve(items);
   }

   /** 测试用：按显示名去重并优先保留有 exe 的条目。 */
   static List<InstalledSoftwareItem> dedupeForTest(List<InstalledSoftwareItem> items) {
      Map<String, InstalledSoftwareItem> byName = new LinkedHashMap<>();

      for (InstalledSoftwareItem item : items) {
         if (!blank(item.name())) {
            String key = item.name().toLowerCase(Locale.ROOT);
            InstalledSoftwareItem existing = byName.get(key);
            if (existing == null) {
               byName.put(key, item);
            } else if (blank(existing.exePath()) && !blank(item.exePath())) {
               byName.put(key, item);
            }
         }
      }

      return new ArrayList<>(byName.values());
   }
}
