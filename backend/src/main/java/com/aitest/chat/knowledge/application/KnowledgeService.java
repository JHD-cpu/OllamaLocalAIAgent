package com.aitest.chat.knowledge.application;












import com.aitest.chat.knowledge.storage.TextStore;
import com.aitest.chat.knowledge.storage.KnowledgeVectorStore;
import com.aitest.chat.knowledge.storage.KnowledgeRepository.StoredDocument;
import com.aitest.chat.knowledge.storage.KnowledgeRepository.StoredChunk;
import com.aitest.chat.knowledge.storage.KnowledgeRepository;
import com.aitest.chat.knowledge.indexing.EmbeddingSemanticChunker;
import com.aitest.chat.knowledge.indexing.DocumentTextReader;
import com.aitest.chat.knowledge.indexing.IndexCancelledException;
import com.aitest.chat.knowledge.indexing.IndexGuard;
import com.aitest.chat.knowledge.indexing.IndexProgress;
import com.aitest.chat.api.KnowledgeHit;
import com.aitest.chat.api.KnowledgeDocumentView;
import com.aitest.chat.api.KnowledgeChunkView;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
   /** 正在入库的文档取消标志：删除时置位，解析/分块循环协作退出。 */
   private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
   private final ExecutorService indexer = Executors.newFixedThreadPool(indexPoolSize(), task -> {
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
      if (!"pdf".equals(extension) && !"txt".equals(extension) && !"md".equals(extension)
            && !"docx".equals(extension) && !"xlsx".equals(extension) && !"xls".equals(extension)) {
         throw new IllegalArgumentException("只支持 pdf、txt、md、docx、xlsx");
      } else if (bytes != null && bytes.length != 0) {
         String id = UUID.randomUUID().toString();
         synchronized (this.gate) {
            Path original = this.textStore.writeOriginal(id, name, bytes);
            KnowledgeRepository.StoredDocument stored = new KnowledgeRepository.StoredDocument(
               id, name, extension, "PROCESSING", null, null, 0, 0, "排队中", original.toString(), null, LocalDateTime.now()
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

   /** 保留原件，清空旧分块/向量后按当前规则重新入库。 */
   public KnowledgeDocumentView rechunk(String id) {
      synchronized (this.gate) {
         KnowledgeRepository.StoredDocument document = this.require(id);
         if ("PROCESSING".equals(document.status())) {
            throw new IllegalArgumentException("文档正在分块中，请稍后再试");
         }
         if (document.originalPath() == null || document.originalPath().isBlank()) {
            throw new IllegalArgumentException("找不到原文件，无法重新分块");
         }
         Path original = Path.of(document.originalPath());
         if (!original.toFile().isFile()) {
            throw new IllegalArgumentException("原文件已丢失，无法重新分块");
         }
         log.info("重新分块 id={} file={} status={}", document.id(), document.filename(), document.status());
         try {
            this.vectorStore.deleteDocument(id);
         }
         catch (RuntimeException ex) {
            log.warn("重新分块前清理向量失败，继续本地清理 id={}", id, ex);
         }
         this.repository.deleteChunksByDocument(id);
         this.textStore.deleteDerived(id);
         this.repository.markProcessing(id);
      }
      this.indexer.execute(() -> this.index(id));
      return view(this.repository.find(id).orElseThrow());
   }

   /** 删除文档的向量、文件与数据库记录；若正在分块则先发取消信号。 */
   public void delete(String id) {
      AtomicBoolean flag = this.cancelFlags.get(id);
      if (flag != null) {
         flag.set(true);
         log.info("已请求停止分块 id={}", id);
      }
      synchronized (this.gate) {
         KnowledgeRepository.StoredDocument document = this.repository.find(id).orElseThrow(() -> new IllegalArgumentException("找不到这个文档"));
         log.info("删除文档 id={} file={} status={}", document.id(), document.filename(), document.status());
         if ("FAILED".equals(document.status())) {
            try {
               this.vectorStore.deleteDocument(id);
            } catch (RuntimeException var6) {
               log.warn("删除失败文档时向量库不可用，仍移除本地记录 id={}", id, var6);
            }
         } else if (!"PROCESSING".equals(document.status())) {
            this.vectorStore.deleteDocument(id);
         } else {
            // 处理中：尽量清掉可能已写入的部分向量，忽略失败
            try {
               this.vectorStore.deleteDocument(id);
            } catch (RuntimeException ex) {
               log.debug("处理中文档删除向量暂不可用 id={}: {}", id, ex.toString());
            }
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

   /** 后台流水线：解析、切分、写库与建向量；删除时协作停止。 */
   private void index(String id) {
      boolean sentToVectorStore = false;
      long started = System.nanoTime();
      AtomicBoolean cancelled = new AtomicBoolean(false);
      this.cancelFlags.put(id, cancelled);
      IndexGuard guard = () -> {
         if (cancelled.get() || this.repository.find(id).isEmpty()) {
            cancelled.set(true);
            throw new IndexCancelledException(id);
         }
      };
      IndexProgress progress = guard.wrap((percent, message, chunkCount) -> {
         try {
            this.repository.updateProgress(id, percent, message, chunkCount);
         }
         catch (RuntimeException ex) {
            log.debug("进度写入失败 id={}: {}", id, ex.toString());
         }
      });

      try {
         KnowledgeRepository.StoredDocument document = this.repository.find(id).orElse(null);
         if (document == null) {
            log.info("文档已不存在，跳过入库 id={}", id);
            return;
         }

         progress.update(2, "开始解析", 0);
         log.info("开始解析 id={} file={}", id, document.filename());
         String text = this.reader.read(document.filename(), Path.of(document.originalPath()), progress, guard);
         if (text.isBlank()) {
            throw new IllegalStateException("没有提取到文字");
         }

         log.info("文字提取完成 id={} chars={}", id, text.length());
         List<String> chunks = this.chunker.split(document.filename(), text, progress, guard);
         log.info("建议分片完成 id={} chunks={}", id, chunks.size());
         synchronized (this.gate) {
            guard.check();
            progress.update(88, "写入分块与向量", chunks.size());
            // 重新分块时先清掉残留分块行与文件，避免序号/向量叠旧数据
            this.repository.deleteChunksByDocument(id);
            this.textStore.deleteDerived(id);
            Path textPath = this.textStore.writeText(id, text);
            List<Document> vectors = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i++) {
               guard.check();
               String chunkId = UUID.randomUUID().toString();
               Path chunkPath = this.textStore.writeChunk(id, i, chunks.get(i));
               this.repository.insertChunk(chunkId, id, i, chunkPath.toString(), chunks.get(i).length());
               vectors.add(new Document(chunkId, chunks.get(i), Map.of("documentId", id, "chunkId", chunkId, "filename", document.filename())));
               if (i == 0 || (i + 1) % 5 == 0 || i + 1 == chunks.size()) {
                  int pct = 88 + (int) Math.round(10.0 * (i + 1) / Math.max(1, chunks.size()));
                  progress.update(Math.min(98, pct), "写入分块 " + (i + 1) + "/" + chunks.size(), i + 1);
               }
            }

            guard.check();
            try {
               this.vectorStore.deleteDocument(id);
            }
            catch (RuntimeException ex) {
               log.debug("写入前清理旧向量 id={}: {}", id, ex.toString());
            }
            sentToVectorStore = true;
            progress.update(98, "写入向量库", chunks.size());
            this.vectorStore.add(vectors);
            guard.check();
            this.repository.markReady(id, text.length(), chunks.size(), textPath.toString());
         }

         log.info("知识库入库完成 id={} chunks={} 耗时={}ms", id, chunks.size(), (System.nanoTime() - started) / 1000000L);
      }
      catch (IndexCancelledException cancelledEx) {
         log.info("文档已删除，已停止分块 id={} 耗时={}ms", id, (System.nanoTime() - started) / 1000000L);
         if (sentToVectorStore) {
            try {
               this.vectorStore.deleteDocument(id);
            }
            catch (RuntimeException ex) {
               log.debug("取消后清理向量失败 id={}: {}", id, ex.toString());
            }
         }
      }
      catch (Exception ex) {
         if (ex.getCause() instanceof IndexCancelledException || cancelled.get()) {
            log.info("文档已删除，已停止分块 id={}", id);
            return;
         }
         log.error("知识库入库失败 id={}", id, ex);
         synchronized (this.gate) {
            if (this.repository.find(id).isPresent()) {
               if (sentToVectorStore) {
                  try {
                     this.vectorStore.deleteDocument(id);
                  }
                  catch (RuntimeException cleanup) {
                     log.warn("清理失败文档的向量没有成功 id={}", id, cleanup);
                  }
               }
               this.repository.markFailed(id, brief(ex));
            }
         }
      }
      finally {
         this.cancelFlags.remove(id, cancelled);
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

   /** 校验分块非空（不做字符数上限，大段由入库分片流程处理）。 */
   private static String cleanChunk(String text) {
      String body = text == null ? "" : text.trim();
      if (body.isEmpty()) {
         throw new IllegalArgumentException("分块内容不能为空");
      }
      return body;
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
         document.progress(),
         document.progressMessage(),
         document.createdAt()
      );
   }

   /** 关闭后台索引线程池。 */
   public void destroy() {
      this.indexer.shutdownNow();
   }

   /** 并行入库线程数：2–6，随 CPU 核数调整。 */
   private static int indexPoolSize() {
      int cores = Runtime.getRuntime().availableProcessors();
      return Math.max(2, Math.min(6, cores));
   }

   /** 原件下载载荷：文件名、MIME 与内容。 */
   public record OriginalFile(String filename, String mediaType, byte[] bytes) {
   }
}
