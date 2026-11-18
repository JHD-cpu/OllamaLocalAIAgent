package com.aitest.chat.knowledge.storage;



import com.aitest.chat.knowledge.embedding.SettingsEmbeddingModel;
import com.aitest.chat.knowledge.config.RagProperties;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 基于 SimpleVectorStore 的本地 JSON 向量库。 */
@Component
public class KnowledgeVectorStore {
   private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStore.class);
   private final RagProperties properties;
   private final SettingsEmbeddingModel embeddingModel;
   private final Object lock = new Object();
   private volatile SimpleVectorStore store;
   private volatile File storeFile;

   /** 注入配置与嵌入模型。 */
   public KnowledgeVectorStore(RagProperties properties, SettingsEmbeddingModel embeddingModel) {
      this.properties = properties;
      this.embeddingModel = embeddingModel;
   }

   /** 写入分块向量并持久化。 */
   public void add(List<Document> documents) {
      if (documents.isEmpty()) {
         log.info("没有向量需要写入");
      } else {
         long started = System.nanoTime();

         try {
            SimpleVectorStore current = this.store();
            current.add(documents);
            this.persist(current);
            log.info("写入向量 {} 条 耗时={}ms", documents.size(), (System.nanoTime() - started) / 1000000L);
         } catch (RuntimeException var5) {
            throw this.down(var5);
         }
      }
   }

   /** 按问题做相似度检索。 */
   public List<Document> search(String question, int topK) {
      long started = System.nanoTime();

      try {
         List<Document> found = this.store().similaritySearch(SearchRequest.builder().query(question).topK(topK).build());
         List<Document> hits = found == null ? List.of() : found;
         log.info("向量检索 topK={} 命中={} 耗时={}ms question=\"{}\"", new Object[]{topK, hits.size(), (System.nanoTime() - started) / 1000000L, preview(question)});
         return hits;
      } catch (RuntimeException var7) {
         throw this.down(var7);
      }
   }

   /** 按 documentId 元数据删除该文档全部向量。 */
   public void deleteDocument(String documentId) {
      log.info("删除向量 documentId={}", documentId);

      try {
         SimpleVectorStore current = this.store();
         current.delete(new FilterExpressionBuilder().eq("documentId", documentId).build());
         this.persist(current);
      } catch (RuntimeException var3) {
         throw this.down(var3);
      }
   }

   /** 按分块 id 删除向量。 */
   public void deleteChunks(List<String> ids) {
      if (!ids.isEmpty()) {
         log.info("删除切块向量 {} 条", ids.size());

         try {
            SimpleVectorStore current = this.store();
            current.delete(ids);
            this.persist(current);
         } catch (RuntimeException var3) {
            throw this.down(var3);
         }
      }
   }

   /** 懒加载或初始化向量库实例。 */
   private SimpleVectorStore store() {
      SimpleVectorStore cached = this.store;
      if (cached != null) {
         return cached;
      } else {
         synchronized (this.lock) {
            if (this.store != null) {
               return this.store;
            } else {
               File file = this.resolveStoreFile();
               this.storeFile = file;

               try {
                  Files.createDirectories(file.toPath().getParent());
               } catch (IOException var6) {
                  throw new IllegalStateException("无法创建向量库目录：" + file.getParent(), var6);
               }

               log.info("打开内嵌向量库 file={} dims={}", file.getAbsolutePath(), this.properties.getEmbeddingDimensions());
               SimpleVectorStore created = SimpleVectorStore.builder(this.embeddingModel).build();
               if (file.isFile() && file.length() > 0L) {
                  created.load(file);
                  log.info("已加载向量库 {}", file.getAbsolutePath());
               } else {
                  this.persist(created);
                  log.info("已初始化空向量库 {}", file.getAbsolutePath());
               }

               this.store = created;
               return created;
            }
         }
      }
   }

   /** 将内存向量库保存到磁盘。 */
   private void persist(SimpleVectorStore current) {
      File file = this.storeFile != null ? this.storeFile : this.resolveStoreFile();
      current.save(file);
   }

   /** 解析向量库文件的绝对路径。 */
   private File resolveStoreFile() {
      Path path = Path.of(
         this.properties.getVectorStoreFile() != null && !this.properties.getVectorStoreFile().isBlank()
            ? this.properties.getVectorStoreFile().trim()
            : "data/vectors.json"
      );
      if (!path.isAbsolute()) {
         path = Path.of(System.getProperty("user.dir")).resolve(path);
      }

      return path.toAbsolutePath().normalize().toFile();
   }

   /** 将底层异常转为对用户可读的 IllegalStateException。 */
   private IllegalStateException down(Throwable ex) {
      String detail = detail(ex);
      if (detail.contains("向量库")) {
         return ex instanceof IllegalStateException state ? state : new IllegalStateException(detail, ex);
      } else {
         log.warn("向量库操作失败：{}", detail, ex);
         if (detail.contains("not found") && detail.contains("model")) {
            return new IllegalStateException("嵌入模型不可用。请先在 Ollama 中拉取设置里的嵌入模型，默认是 bge-m3。", ex);
         } else {
            String brief = detail.replace('\n', ' ').trim();
            if (brief.length() > 200) {
               brief = brief.substring(0, 200);
            }

            return new IllegalStateException(brief.isEmpty() ? "向量库操作失败。" : "向量库操作失败：" + brief, ex);
         }
      }
   }

   /** 日志用问题单行预览（最多 80 字）。 */
   private static String preview(String text) {
      if (text != null && !text.isBlank()) {
         String oneLine = text.replaceAll("\\s+", " ").trim();
         return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
      } else {
         return "";
      }
   }

   /** 拼接异常链与 HTTP 响应体文案。 */
   private static String detail(Throwable ex) {
      StringBuilder text = new StringBuilder();

      for (Throwable current = ex; current != null; current = current.getCause()) {
         if (current.getMessage() != null) {
            text.append(current.getMessage()).append('\n');
         }

         if (current instanceof WebClientResponseException response) {
            text.append(response.getResponseBodyAsString()).append('\n');
         }
      }

      return text.toString();
   }
}
