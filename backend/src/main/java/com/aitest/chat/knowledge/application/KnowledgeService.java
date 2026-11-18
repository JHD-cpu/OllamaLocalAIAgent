package com.aitest.chat.knowledge.application;












import com.aitest.chat.knowledge.storage.TextStore;
import com.aitest.chat.knowledge.storage.KnowledgeVectorStore;
import com.aitest.chat.knowledge.storage.KnowledgeRepository.StoredDocument;
import com.aitest.chat.knowledge.storage.KnowledgeRepository.StoredChunk;
import com.aitest.chat.knowledge.storage.KnowledgeRepository;
import com.aitest.chat.knowledge.indexing.EmbeddingSemanticChunker;
import com.aitest.chat.knowledge.indexing.DocumentTextReader;
import com.aitest.chat.knowledge.config.RagProperties;
import com.aitest.chat.api.KnowledgeHit;
import com.aitest.chat.api.KnowledgeDocumentView;
import com.aitest.chat.api.KnowledgeChunkView;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/** 知识库上传、索引、分块管理与向量检索门面。 */
@Service
public class KnowledgeService implements DisposableBean {
   private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
   private final KnowledgeRepository repository;
   private final TextStore textStore;
   private final DocumentTextReader reader;
   private final KnowledgeVectorStore vectorStore;
   private final EmbeddingSemanticChunker chunker;
   private final Object gate = new Object();
   private final ExecutorService indexer = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "knowledge-index");
      thread.setDaemon(true);
      return thread;
   });

   /** 注入仓储、文本库、解析与向量组件。 */
   public KnowledgeService(
      KnowledgeRepository repository, TextStore textStore, DocumentTextReader reader, KnowledgeVectorStore vectorStore, EmbeddingSemanticChunker chunker
   ) {
      this.repository = repository;
      this.textStore = textStore;
      this.reader = reader;
      this.vectorStore = vectorStore;
      this.chunker = chunker;
   }

   /** 校验并保存原件，异步触发入库。 */
   public KnowledgeDocumentView upload(String filename, byte[] bytes) {
      String name = filename == null ? "" : Path.of(filename).getFileName().toString().trim();
      int size = bytes == null ? 0 : bytes.length;
      log.info("收到上传 file={} bytes={}", name, size);
      String extension = DocumentTextReader.extension(name);
      if (!"pdf".equals(extension) && !"txt".equals(extension) && !"md".equals(extension)) {
         throw new IllegalArgumentException("只支持 pdf、txt、md");
      } else if (bytes != null && bytes.length != 0) {
         String id = UUID.randomUUID().toString();
         synchronized (this.gate) {
            Path original = this.textStore.writeOriginal(id, name, bytes);
            KnowledgeRepository.StoredDocument stored = new KnowledgeRepository.StoredDocument(
               id, name, extension, "PROCESSING", null, null, null, original.toString(), null, LocalDateTime.now()
            );
            this.repository.insertDocument(stored);
         }

         this.indexer.execute(() -> this.index(id));
         log.info("上传已排队 id={} file={}", id, name);
         return view(this.repository.find(id).orElseThrow());
      } else {
         throw new IllegalArgumentException("文件是空的");
      }
   }

   /** 列出全部文档摘要。 */
   public List<KnowledgeDocumentView> list() {
      return this.repository.list().stream().map(KnowledgeService::view).toList();
   }

   /** 删除文档的向量、文件与数据库记录。 */
   public void delete(String id) {
      synchronized (this.gate) {
         KnowledgeRepository.StoredDocument document = this.repository.find(id).orElseThrow(() -> new IllegalArgumentException("找不到这个文档"));
         log.info("删除文档 id={} file={} status={}", new Object[]{document.id(), document.filename(), document.status()});
         if ("FAILED".equals(document.status())) {
            try {
               this.vectorStore.deleteDocument(id);
            } catch (RuntimeException var6) {
               log.warn("删除失败文档时向量库不可用，仍移除本地记录 id={}", id, var6);
            }
         } else {
            this.vectorStore.deleteDocument(id);
         }

         this.textStore.deleteAll(id);
         this.repository.delete(document.id());
      }
   }

   /** 返回已上传原件的字节与 MIME。 */
   public KnowledgeService.OriginalFile original(String id) {
      synchronized (this.gate) {
         KnowledgeRepository.StoredDocument document = this.require(id);
         log.info("读取原件 id={} file={}", document.id(), document.filename());
         byte[] bytes = this.textStore.readBytes(Path.of(document.originalPath()));
         return new KnowledgeService.OriginalFile(document.filename(), mediaType(document.mediaType()), bytes);
      }
   }

   /** 读取 READY 文档的全部分块正文。 */
   public List<KnowledgeChunkView> chunks(String id) {
      synchronized (this.gate) {
         KnowledgeRepository.StoredDocument document = this.requireReady(id);
         log.info("读取分块 id={} file={}", document.id(), document.filename());
         List<KnowledgeChunkView> views = new ArrayList<>();

         for (KnowledgeRepository.StoredChunk chunk : this.repository.listChunks(id)) {
            String text = this.textStore.readString(Path.of(chunk.textPath()));
            views.add(new KnowledgeChunkView(chunk.id(), chunk.index(), text.length(), text));
         }

         return views;
      }
   }

   /** 更新分块文本并刷新对应向量。 */
   public KnowledgeChunkView updateChunk(String documentId, String chunkId, String text) {
      String body = cleanChunk(text);
      synchronized (this.gate) {
         KnowledgeRepository.StoredDocument document = this.requireReady(documentId);
         KnowledgeRepository.StoredChunk chunk = this.repository.findChunk(documentId, chunkId).orElseThrow(() -> new IllegalArgumentException("找不到这个分块"));
         log.info("更新分块 id={} chunk={} chars={}", new Object[]{documentId, chunkId, body.length()});
         this.textStore.writeChunk(documentId, chunk.index(), body);
         this.repository.updateChunkCount(chunkId, body.length());
         this.vectorStore.deleteChunks(List.of(chunkId));
         this.vectorStore.add(List.of(vector(document, chunkId, body)));
         return new KnowledgeChunkView(chunkId, chunk.index(), body.length(), body);
      }
   }

   /** 删除单个分块及其向量。 */
   public void deleteChunk(String documentId, String chunkId) {
      synchronized (this.gate) {
         this.requireReady(documentId);
         KnowledgeRepository.StoredChunk chunk = this.repository.findChunk(documentId, chunkId).orElseThrow(() -> new IllegalArgumentException("找不到这个分块"));
         log.info("删除分块 id={} chunk={} index={}", new Object[]{documentId, chunkId, chunk.index()});
         this.vectorStore.deleteChunks(List.of(chunkId));
         this.textStore.deleteFile(Path.of(chunk.textPath()));
         this.repository.deleteChunk(chunkId);
         this.repository.refreshChunkCount(documentId);
      }
   }

   /** 在文档末尾追加手工分块。 */
   public KnowledgeChunkView addChunk(String documentId, String text) {
      String body = cleanChunk(text);
      synchronized (this.gate) {
         KnowledgeRepository.StoredDocument document = this.requireReady(documentId);
         int index = this.repository.nextChunkIndex(documentId);
         String chunkId = UUID.randomUUID().toString();
         log.info("新增分块 id={} chunk={} index={} chars={}", new Object[]{documentId, chunkId, index, body.length()});
         Path path = this.textStore.writeChunk(documentId, index, body);
         this.repository.insertChunk(chunkId, documentId, index, path.toString(), body.length());
         this.vectorStore.add(List.of(vector(document, chunkId, body)));
         this.repository.refreshChunkCount(documentId);
         return new KnowledgeChunkView(chunkId, index, body.length(), body);
      }
   }

   /** 向量检索问答上下文片段。 */
   public List<KnowledgeHit> retrieve(String question, int topK) {
      if (question != null && !question.isBlank()) {
         List<KnowledgeHit> hits = new ArrayList<>();

         for (Document document : this.vectorStore.search(question, topK)) {
            String text = document.getText() == null ? "" : document.getText().trim();
            if (!text.isEmpty()) {
               Object filename = document.getMetadata().get("filename");
               hits.add(new KnowledgeHit(filename == null ? "资料" : filename.toString(), text));
            }
         }

         return hits;
      } else {
         return List.of();
      }
   }

   /** 后台流水线：解析、切分、写库与建向量。 */
   private void index(String id) {
      boolean sentToVectorStore = false;
      long started = System.nanoTime();

      try {
         KnowledgeRepository.StoredDocument document = this.repository.find(id).orElse(null);
         if (document == null) {
            log.info("文档已不存在，跳过入库 id={}", id);
            return;
         }

         log.info("开始解析 id={} file={}", id, document.filename());
         String text = this.reader.read(document.filename(), Path.of(document.originalPath()));
         if (text.isBlank()) {
            throw new IllegalStateException("没有提取到文字");
         }

         log.info("文字提取完成 id={} chars={}", id, text.length());
         List<String> chunks = this.chunker.split(text);
         log.info("语义切分完成 id={} chunks={}", id, chunks.size());
         synchronized (this.gate) {
            if (this.repository.find(id).isEmpty()) {
               log.info("解析期间文档已删除，取消写入 id={}", id);
               return;
            }

            Path textPath = this.textStore.writeText(id, text);
            List<Document> vectors = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
               String chunkId = UUID.randomUUID().toString();
               Path chunkPath = this.textStore.writeChunk(id, i, chunks.get(i));
               this.repository.insertChunk(chunkId, id, i, chunkPath.toString(), chunks.get(i).length());
               vectors.add(new Document(chunkId, chunks.get(i), Map.of("documentId", id, "chunkId", chunkId, "filename", document.filename())));
            }

            sentToVectorStore = true;
            this.vectorStore.add(vectors);
            this.repository.markReady(id, text.length(), chunks.size(), textPath.toString());
         }

         log.info("知识库入库完成 id={} chunks={} 耗时={}ms", new Object[]{id, chunks.size(), (System.nanoTime() - started) / 1000000L});
      } catch (Exception var19) {
         Exception ex = var19;
         log.warn("知识库入库失败 id={}", id, var19);
         synchronized (this.gate) {
            if (this.repository.find(id).isPresent()) {
               if (sentToVectorStore) {
                  try {
                     this.vectorStore.deleteDocument(id);
                  } catch (RuntimeException var16) {
                     log.warn("清理失败文档的向量没有成功 id={}", id, var16);
                  }
               }

               this.repository.markFailed(id, brief(ex));
            }
         }
      }
   }

   /** 截断异常信息供 FAILED 状态展示。 */
   private static String brief(Throwable ex) {
      String message = ex.getMessage() == null ? "处理失败" : ex.getMessage().replace('\n', ' ').trim();
      return message.length() > 1000 ? message.substring(0, 1000) : message;
   }

   /** 按 id 取文档，不存在则抛错。 */
   private KnowledgeRepository.StoredDocument require(String id) {
      return this.repository.find(id).orElseThrow(() -> new IllegalArgumentException("找不到这个文档"));
   }

   /** 要求文档状态为 READY。 */
   private KnowledgeRepository.StoredDocument requireReady(String id) {
      KnowledgeRepository.StoredDocument document = this.require(id);
      if (!"READY".equals(document.status())) {
         throw new IllegalArgumentException("文档还没处理完成");
      } else {
         return document;
      }
   }

   /** 校验分块非空且不超过长度上限。 */
   private static String cleanChunk(String text) {
      String body = text == null ? "" : text.trim();
      if (body.isEmpty()) {
         throw new IllegalArgumentException("分块内容不能为空");
      } else if (body.length() > 20000) {
         throw new IllegalArgumentException("分块不能超过 20000 个字符");
      } else {
         return body;
      }
   }

   /** 构造带元数据的 Spring AI Document。 */
   private static Document vector(KnowledgeRepository.StoredDocument document, String chunkId, String text) {
      return new Document(chunkId, text, Map.of("documentId", document.id(), "chunkId", chunkId, "filename", document.filename()));
   }

   /** 按扩展名返回下载 MIME。 */
   private static String mediaType(String extension) {
      if ("pdf".equals(extension)) {
         return "application/pdf";
      } else {
         return "md".equals(extension) ? "text/markdown;charset=UTF-8" : "text/plain;charset=UTF-8";
      }
   }

   /** 将存储记录转为 API 视图。 */
   private static KnowledgeDocumentView view(KnowledgeRepository.StoredDocument document) {
      return new KnowledgeDocumentView(
         document.id(),
         document.filename(),
         document.mediaType(),
         document.status(),
         document.errorMessage(),
         document.charCount(),
         document.chunkCount(),
         document.createdAt()
      );
   }

   /** 关闭后台索引线程池。 */
   public void destroy() {
      this.indexer.shutdown();
   }

   /** 原件下载载荷：文件名、MIME 与内容。 */
   public record OriginalFile(String filename, String mediaType, byte[] bytes) {
   }
}
