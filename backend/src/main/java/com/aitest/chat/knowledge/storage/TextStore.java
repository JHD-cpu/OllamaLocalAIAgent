package com.aitest.chat.knowledge.storage;


import com.aitest.chat.knowledge.config.RagProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** 知识库原件、全文与分块文本的本地文件存储。 */
@Component
public class TextStore {
   private final Path root;

   /** 按配置初始化目录结构。 */
   public TextStore(RagProperties properties) {
      Path configured = Path.of(properties.getTextDir() != null && !properties.getTextDir().isBlank() ? properties.getTextDir().trim() : "data/knowledge");
      if (!configured.isAbsolute()) {
         configured = Path.of(System.getProperty("user.dir")).resolve(configured);
      }

      this.root = configured.toAbsolutePath().normalize();

      try {
         Files.createDirectories(this.root.resolve("originals"));
         Files.createDirectories(this.root.resolve("texts"));
         Files.createDirectories(this.root.resolve("chunks"));
      } catch (IOException var4) {
         throw new IllegalStateException("无法创建文本库目录 " + this.root, var4);
      }
   }

   /** 写入上传原件字节流。 */
   public Path writeOriginal(String id, String filename, byte[] bytes) {
      Path path = this.root.resolve("originals").resolve(id).resolve(safeName(filename));
      write(path, bytes);
      return path;
   }

   /** 写入文档解析后的全文。 */
   public Path writeText(String id, String text) {
      Path path = this.root.resolve("texts").resolve(id + ".txt");
      write(path, text.getBytes(StandardCharsets.UTF_8));
      return path;
   }

   /** 写入指定序号的分块文本。 */
   public Path writeChunk(String id, int index, String text) {
      Path path = this.root.resolve("chunks").resolve(id).resolve(index + ".txt");
      write(path, text.getBytes(StandardCharsets.UTF_8));
      return path;
   }

   /** 以字符串读取库内文本文件。 */
   public String readString(Path path) {
      this.checkInside(path);

      try {
         return Files.readString(path);
      } catch (IOException var3) {
         throw new IllegalStateException("读取文本失败：" + path, var3);
      }
   }

   /** 以字节读取库内文件。 */
   public byte[] readBytes(Path path) {
      this.checkInside(path);

      try {
         return Files.readAllBytes(path);
      } catch (IOException var3) {
         throw new IllegalStateException("读取文件失败：" + path, var3);
      }
   }

   /** 删除库内单个文件。 */
   public void deleteFile(Path path) {
      this.checkInside(path);

      try {
         Files.deleteIfExists(path);
      } catch (IOException var3) {
         throw new IllegalStateException("删除文本失败：" + path, var3);
      }
   }

   /** 删除某文档的全部本地文本与原件。 */
   public void deleteAll(String id) {
      deleteTree(this.root.resolve("originals").resolve(id));
      deleteDerived(id);
   }

   /** 删除解析全文与分块文件，保留上传原件（供重新分块）。 */
   public void deleteDerived(String id) {
      deleteTree(this.root.resolve("chunks").resolve(id));
      try {
         Files.deleteIfExists(this.root.resolve("texts").resolve(id + ".txt"));
      }
      catch (IOException ex) {
         throw new IllegalStateException("删除文本失败", ex);
      }
   }

   /** 创建父目录并写入字节。 */
   private static void write(Path path, byte[] bytes) {
      try {
         Files.createDirectories(path.getParent());
         Files.write(path, bytes);
      } catch (IOException var3) {
         throw new IllegalStateException("写入文本库失败：" + path, var3);
      }
   }

   /** 递归删除目录树。 */
   private static void deleteTree(Path path) {
      if (Files.exists(path)) {
         try {
            try (Stream<Path> walk = Files.walk(path)) {
               walk.sorted(Comparator.reverseOrder()).forEach(file -> {
                  try {
                     Files.deleteIfExists(file);
                  } catch (IOException var2) {
                     throw new IllegalStateException("删除文本失败：" + file, var2);
                  }
               });
            }
         } catch (IOException var6) {
            throw new IllegalStateException("删除文本失败：" + path, var6);
         }
      }
   }

   /** 校验路径必须在文本库根目录下。 */
   private void checkInside(Path path) {
      Path root = this.root.toAbsolutePath().normalize();
      Path file = path.toAbsolutePath().normalize();
      if (!file.startsWith(root)) {
         throw new IllegalArgumentException("文件不在文本库里");
      }
   }

   /** 将文件名净化为安全本地名。 */
   private static String safeName(String filename) {
      String name = filename == null ? "" : Path.of(filename).getFileName().toString();
      name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
      if (name.isBlank()) {
         name = "file";
      }

      return name.length() > 120 ? name.substring(name.length() - 120) : name;
   }
}
