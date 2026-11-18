package com.aitest.chat.api;



import com.aitest.chat.knowledge.application.KnowledgeService.OriginalFile;
import com.aitest.chat.knowledge.application.KnowledgeService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 知识库文档与分块的 REST API。 */
@RestController
@RequestMapping({"/api/knowledge"})
public class KnowledgeController {
   private final KnowledgeService knowledgeService;

   /** 注入知识库应用服务。 */
   public KnowledgeController(KnowledgeService knowledgeService) {
      this.knowledgeService = knowledgeService;
   }

   /** 列出全部文档元数据。 */
   @GetMapping({"/documents"})
   public List<KnowledgeDocumentView> documents() {
      return this.knowledgeService.list();
   }

   /** 上传文件并入队解析与向量化。 */
   @PostMapping(
      value = {"/documents"},
      consumes = {"multipart/form-data"}
   )
   public Mono<KnowledgeDocumentView> upload(@RequestPart("file") FilePart file) {
      return DataBufferUtils.join(file.content())
         .timeout(Duration.ofMinutes(30L))
         .switchIfEmpty(Mono.error(new IllegalArgumentException("请选择文件")))
         .publishOn(Schedulers.boundedElastic())
         .map(buffer -> {
            KnowledgeDocumentView var3;
            try {
               var3 = this.knowledgeService.upload(file.filename(), toBytes(buffer));
            } finally {
               DataBufferUtils.release(buffer);
            }

            return var3;
         });
   }

   /** 按 id 删除文档及其分块。 */
   @DeleteMapping({"/documents/{id}"})
   @ResponseStatus(HttpStatus.NO_CONTENT)
   public void delete(@PathVariable String id) {
      this.knowledgeService.delete(id);
   }

   /** 保留原件，按当前规则重新分块入库。 */
   @PostMapping({"/documents/{id}/rechunk"})
   public Mono<KnowledgeDocumentView> rechunk(@PathVariable String id) {
      return Mono.fromCallable(() -> this.knowledgeService.rechunk(id)).subscribeOn(Schedulers.boundedElastic());
   }

   /** 返回原始上传文件字节流（inline 展示）。 */
   @GetMapping({"/documents/{id}/original", "/documents/{id}/original/{filename:.+}"})
   public Mono<ResponseEntity<byte[]>> original(@PathVariable String id) {
      return Mono.fromCallable(
            () -> {
               KnowledgeService.OriginalFile file = this.knowledgeService.original(id);
               ContentDisposition disposition = ContentDisposition.inline().filename(file.filename(), StandardCharsets.UTF_8).build();
               return ((BodyBuilder)ResponseEntity.ok()
                     .contentType(MediaType.parseMediaType(file.mediaType()))
                     .header("Content-Disposition", new String[]{disposition.toString()}))
                  .body(file.bytes());
            }
         )
         .subscribeOn(Schedulers.boundedElastic());
   }

   /** 列出文档下全部分块。 */
   @GetMapping({"/documents/{id}/chunks"})
   public Mono<List<KnowledgeChunkView>> chunks(@PathVariable String id) {
      return Mono.fromCallable(() -> this.knowledgeService.chunks(id)).subscribeOn(Schedulers.boundedElastic());
   }

   /** 为文档新增一段分块文本。 */
   @PostMapping({"/documents/{id}/chunks"})
   public Mono<KnowledgeChunkView> addChunk(@PathVariable String id, @RequestBody ChunkTextRequest request) {
      return Mono.fromCallable(() -> this.knowledgeService.addChunk(id, request == null ? null : request.text())).subscribeOn(Schedulers.boundedElastic());
   }

   /** 更新指定分块正文。 */
   @PutMapping({"/documents/{id}/chunks/{chunkId}"})
   public Mono<KnowledgeChunkView> updateChunk(@PathVariable String id, @PathVariable String chunkId, @RequestBody ChunkTextRequest request) {
      return Mono.fromCallable(() -> this.knowledgeService.updateChunk(id, chunkId, request == null ? null : request.text()))
         .subscribeOn(Schedulers.boundedElastic());
   }

   /** 删除单个分块。 */
   @DeleteMapping({"/documents/{id}/chunks/{chunkId}"})
   @ResponseStatus(HttpStatus.NO_CONTENT)
   public Mono<Void> deleteChunk(@PathVariable String id, @PathVariable String chunkId) {
      return Mono.fromRunnable(() -> this.knowledgeService.deleteChunk(id, chunkId)).subscribeOn(Schedulers.boundedElastic()).then();
   }

   /** 将 Reactive DataBuffer 读入字节数组。 */
   private static byte[] toBytes(DataBuffer buffer) {
      byte[] bytes = new byte[buffer.readableByteCount()];
      buffer.read(bytes);
      return bytes;
   }
}