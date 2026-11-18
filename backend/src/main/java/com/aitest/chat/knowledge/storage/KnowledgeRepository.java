package com.aitest.chat.knowledge.storage;



import com.aitest.chat.api.KnowledgeDocumentView;
import com.aitest.chat.api.KnowledgeChunkView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 知识库文档与分块的 JDBC 持久化。 */
@Repository
public class KnowledgeRepository {
   private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
   private final JdbcClient jdbc;

   /** 注入 JDBC 客户端。 */
   public KnowledgeRepository(JdbcClient jdbc) {
      this.jdbc = jdbc;
   }

   /** 插入新文档元数据（处理中状态）。 */
   public void insertDocument(KnowledgeRepository.StoredDocument document) {
      this.jdbc
         .sql(
            "INSERT INTO rag_document\n\t(id, filename, media_type, status, original_path, created_at)\nVALUES\n\t(:id, :filename, :mediaType, :status, :originalPath, :createdAt)\n"
         )
         .param("id", document.id())
         .param("filename", document.filename())
         .param("mediaType", document.mediaType())
         .param("status", document.status())
         .param("originalPath", document.originalPath())
         .param("createdAt", document.createdAt().format(CREATED_AT))
         .update();
   }

   /** 插入一条分块记录。 */
   public void insertChunk(String id, String documentId, int index, String textPath, int charCount) {
      this.jdbc
         .sql("INSERT INTO rag_chunk (id, document_id, chunk_index, text_path, char_count)\nVALUES (:id, :documentId, :chunkIndex, :textPath, :charCount)\n")
         .param("id", id)
         .param("documentId", documentId)
         .param("chunkIndex", index)
         .param("textPath", textPath)
         .param("charCount", charCount)
         .update();
   }

   /** 标记文档入库成功并写入统计字段。 */
   public void markReady(String id, int charCount, int chunkCount, String textPath) {
      this.jdbc
         .sql(
            "UPDATE rag_document\nSET status = 'READY', error_message = NULL, char_count = :charCount,\n\tchunk_count = :chunkCount, text_path = :textPath\nWHERE id = :id\n"
         )
         .param("charCount", charCount)
         .param("chunkCount", chunkCount)
         .param("textPath", textPath)
         .param("id", id)
         .update();
   }

   /** 标记文档处理失败及错误摘要。 */
   public void markFailed(String id, String error) {
      this.jdbc.sql("UPDATE rag_document SET status = 'FAILED', error_message = :error WHERE id = :id").param("error", error).param("id", id).update();
   }

   /** 按创建时间倒序列出全部文档。 */
   public List<KnowledgeRepository.StoredDocument> list() {
      return this.jdbc
         .sql(
            "SELECT id, filename, media_type, status, error_message, char_count, chunk_count,\n\toriginal_path, text_path, created_at\nFROM rag_document\nORDER BY created_at DESC\n"
         )
         .query((rs, row) -> map(rs))
         .list();
   }

   /** 按 id 查询文档。 */
   public Optional<KnowledgeRepository.StoredDocument> find(String id) {
      return this.jdbc
         .sql(
            "SELECT id, filename, media_type, status, error_message, char_count, chunk_count,\n\toriginal_path, text_path, created_at\nFROM rag_document\nWHERE id = :id\n"
         )
         .param("id", id)
         .query((rs, row) -> map(rs))
         .optional();
   }

   /** 删除文档及其全部分块行。 */
   public void delete(String id) {
      this.jdbc.sql("DELETE FROM rag_chunk WHERE document_id = :id").param("id", id).update();
      this.jdbc.sql("DELETE FROM rag_document WHERE id = :id").param("id", id).update();
   }

   /** 列出文档下所有分块（按序号排序）。 */
   public List<KnowledgeRepository.StoredChunk> listChunks(String documentId) {
      return this.jdbc
         .sql("SELECT id, document_id, chunk_index, text_path, char_count\nFROM rag_chunk\nWHERE document_id = :documentId\nORDER BY chunk_index\n")
         .param("documentId", documentId)
         .query(
            (rs, row) -> new KnowledgeRepository.StoredChunk(
               rs.getString("id"), rs.getString("document_id"), rs.getInt("chunk_index"), rs.getString("text_path"), rs.getInt("char_count")
            )
         )
         .list();
   }

   /** 在文档内按分块 id 查询。 */
   public Optional<KnowledgeRepository.StoredChunk> findChunk(String documentId, String chunkId) {
      return this.jdbc
         .sql("SELECT id, document_id, chunk_index, text_path, char_count\nFROM rag_chunk\nWHERE document_id = :documentId AND id = :id\n")
         .param("documentId", documentId)
         .param("id", chunkId)
         .query(
            (rs, row) -> new KnowledgeRepository.StoredChunk(
               rs.getString("id"), rs.getString("document_id"), rs.getInt("chunk_index"), rs.getString("text_path"), rs.getInt("char_count")
            )
         )
         .optional();
   }

   /** 返回下一个可用分块序号。 */
   public int nextChunkIndex(String documentId) {
      Integer max = (Integer)this.jdbc
         .sql("SELECT MAX(chunk_index) FROM rag_chunk WHERE document_id = :documentId")
         .param("documentId", documentId)
         .query(Integer.class)
         .optional()
         .orElse(null);
      return max == null ? 0 : max + 1;
   }

   /** 更新分块字符数。 */
   public void updateChunkCount(String id, int charCount) {
      this.jdbc.sql("UPDATE rag_chunk SET char_count = :charCount WHERE id = :id").param("charCount", charCount).param("id", id).update();
   }

   /** 删除单条分块记录。 */
   public void deleteChunk(String id) {
      this.jdbc.sql("DELETE FROM rag_chunk WHERE id = :id").param("id", id).update();
   }

   /** 按实际分块数刷新文档 chunk_count。 */
   public void refreshChunkCount(String documentId) {
      Integer count = (Integer)this.jdbc
         .sql("SELECT COUNT(*) FROM rag_chunk WHERE document_id = :documentId")
         .param("documentId", documentId)
         .query(Integer.class)
         .single();
      this.jdbc.sql("UPDATE rag_document SET chunk_count = :count WHERE id = :id").param("count", count).param("id", documentId).update();
   }

   /** 将文档查询行映射为记录。 */
   private static KnowledgeRepository.StoredDocument map(ResultSet rs) throws SQLException {
      return new KnowledgeRepository.StoredDocument(
         rs.getString("id"),
         rs.getString("filename"),
         rs.getString("media_type"),
         rs.getString("status"),
         rs.getString("error_message"),
         (Integer)rs.getObject("char_count"),
         (Integer)rs.getObject("chunk_count"),
         rs.getString("original_path"),
         rs.getString("text_path"),
         readCreatedAt(rs.getString("created_at"))
      );
   }

   /** 兼容多种格式的 created_at 字符串。 */
   static LocalDateTime readCreatedAt(String raw) {
      if (raw != null && !raw.isBlank()) {
         String value = raw.trim();
         if (value.chars().allMatch(Character::isDigit)) {
            try {
               return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(value)), ZoneId.systemDefault());
            } catch (RuntimeException var5) {
               return LocalDateTime.now();
            }
         } else {
            try {
               return LocalDateTime.parse(value, CREATED_AT);
            } catch (DateTimeParseException var6) {
               try {
                  return LocalDateTime.parse(value.contains("T") ? value : value.replace(' ', 'T'));
               } catch (DateTimeParseException var4) {
                  return LocalDateTime.now();
               }
            }
         }
      } else {
         return LocalDateTime.now();
      }
   }

   /** 数据库中的分块元数据。 */
   public record StoredChunk(String id, String documentId, int index, String textPath, int charCount) {
   }

   /** 数据库中的文档元数据。 */
   public record StoredDocument(
      String id,
      String filename,
      String mediaType,
      String status,
      String errorMessage,
      Integer charCount,
      Integer chunkCount,
      String originalPath,
      String textPath,
      LocalDateTime createdAt
   ) {
   }
}
