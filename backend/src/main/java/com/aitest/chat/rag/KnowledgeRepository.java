package com.aitest.chat.rag;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** MySQL 里的文档和切块元数据。正文在文本库，向量在 Qdrant。 */
@Repository
class KnowledgeRepository {

	private final JdbcClient jdbc;

	KnowledgeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insertDocument(StoredDocument document) {
		this.jdbc.sql("""
				INSERT INTO rag_document
					(id, filename, media_type, status, original_path, created_at)
				VALUES
					(:id, :filename, :mediaType, :status, :originalPath, :createdAt)
				""")
			.param("id", document.id())
			.param("filename", document.filename())
			.param("mediaType", document.mediaType())
			.param("status", document.status())
			.param("originalPath", document.originalPath())
			.param("createdAt", Timestamp.valueOf(document.createdAt()))
			.update();
	}

	void insertChunk(String id, String documentId, int index, String textPath, int charCount) {
		this.jdbc.sql("""
				INSERT INTO rag_chunk (id, document_id, chunk_index, text_path, char_count)
				VALUES (:id, :documentId, :chunkIndex, :textPath, :charCount)
				""")
			.param("id", id)
			.param("documentId", documentId)
			.param("chunkIndex", index)
			.param("textPath", textPath)
			.param("charCount", charCount)
			.update();
	}

	void markReady(String id, int charCount, int chunkCount, String textPath) {
		this.jdbc.sql("""
				UPDATE rag_document
				SET status = 'READY', error_message = NULL, char_count = :charCount,
					chunk_count = :chunkCount, text_path = :textPath
				WHERE id = :id
				""")
			.param("charCount", charCount)
			.param("chunkCount", chunkCount)
			.param("textPath", textPath)
			.param("id", id)
			.update();
	}

	void markFailed(String id, String error) {
		this.jdbc.sql("UPDATE rag_document SET status = 'FAILED', error_message = :error WHERE id = :id")
			.param("error", error)
			.param("id", id)
			.update();
	}

	List<StoredDocument> list() {
		return this.jdbc.sql("""
				SELECT id, filename, media_type, status, error_message, char_count, chunk_count,
					original_path, text_path, created_at
				FROM rag_document
				ORDER BY created_at DESC
				""").query((rs, row) -> map(rs)).list();
	}

	Optional<StoredDocument> find(String id) {
		return this.jdbc.sql("""
				SELECT id, filename, media_type, status, error_message, char_count, chunk_count,
					original_path, text_path, created_at
				FROM rag_document
				WHERE id = :id
				""").param("id", id).query((rs, row) -> map(rs)).optional();
	}

	void delete(String id) {
		this.jdbc.sql("DELETE FROM rag_document WHERE id = :id").param("id", id).update();
	}

	private static StoredDocument map(java.sql.ResultSet rs) throws java.sql.SQLException {
		Timestamp createdAt = rs.getTimestamp("created_at");
		return new StoredDocument(rs.getString("id"), rs.getString("filename"), rs.getString("media_type"),
				rs.getString("status"), rs.getString("error_message"), (Integer) rs.getObject("char_count"),
				(Integer) rs.getObject("chunk_count"), rs.getString("original_path"), rs.getString("text_path"),
				createdAt.toLocalDateTime());
	}

	record StoredDocument(String id, String filename, String mediaType, String status, String errorMessage,
			Integer charCount, Integer chunkCount, String originalPath, String textPath, LocalDateTime createdAt) {
	}

}
