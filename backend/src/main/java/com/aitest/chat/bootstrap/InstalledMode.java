package com.aitest.chat.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 安装/桌面模式下准备用户数据目录并覆盖 JVM 属性。 */
public final class InstalledMode {
   /** 禁止实例化。 */
   private InstalledMode() {
   }

   /** 若启用安装或桌面标志，则创建目录并设置数据源与路径属性。 */
   public static void prepare() {
      if (Boolean.getBoolean("app.installed") || Boolean.getBoolean("app.desktop")) {
         Path home = home();

         try {
            Files.createDirectories(home.resolve("data"));
            Files.createDirectories(home.resolve("data").resolve("knowledge"));
            Files.createDirectories(home.resolve("data").resolve("workspace"));
            Files.createDirectories(home.resolve("deps"));
            Files.createDirectories(home.resolve("logs"));
            seedSettings(home.resolve("data").resolve("settings.json"));
         } catch (IOException var3) {
            throw new IllegalStateException("无法准备数据目录 " + home, var3);
         }

         Path data = home.resolve("data").toAbsolutePath().normalize();
         Path deps = home.resolve("deps").toAbsolutePath().normalize();
         String db = data.resolve("rag.db").toString().replace('\\', '/');
         System.setProperty("user.dir", home.toAbsolutePath().toString());
         String logFile = home.resolve("logs").resolve("app.log").toAbsolutePath().toString();
         System.setProperty("logging.file.name", logFile);
         System.setProperty("LOG_FILE", logFile);
         System.setProperty("spring.datasource.url", "jdbc:sqlite:" + db);
         System.setProperty("app.settings-file", data.resolve("settings.json").toString());
         System.setProperty("app.software-file", data.resolve("installed-software.json").toString());
         System.setProperty("app.deps-dir", deps.toString());
         System.setProperty("app.rag.text-dir", data.resolve("knowledge").toString());
         System.setProperty("app.rag.vector-store-file", data.resolve("vectors.json").toString());
         System.setProperty("app.ollama.code-workspace", data.resolve("workspace").toString());
      }
   }

   /** 解析 OllamaChat 用户主目录（优先 APPDATA）。 */
   private static Path home() {
      String roaming = System.getenv("APPDATA");
      return roaming != null && !roaming.isBlank() ? Path.of(roaming, "OllamaChat") : Path.of(System.getProperty("user.home"), "OllamaChat");
   }

   /** 若本地尚无 settings.json，则从 classpath 复制模板。 */
   private static void seedSettings(Path target) throws IOException {
      if (!Files.exists(target)) {
         try (InputStream in = InstalledMode.class.getResourceAsStream("/installed-settings.json")) {
            if (in == null) {
               return;
            }

            Files.copy(in, target);
         }
      }
   }
}