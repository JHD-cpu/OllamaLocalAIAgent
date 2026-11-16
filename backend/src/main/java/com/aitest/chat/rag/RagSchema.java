package com.aitest.chat.rag;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时建好知识库表。库本身由 JDBC 地址上的 createDatabaseIfNotExist 创建。
 */
@Component
class RagSchema {

	RagSchema(JdbcTemplate jdbc) {
		jdbc.execute("""
				CREATE TABLE IF NOT EXISTS rag_document (
					id VARCHAR(36) NOT NULL PRIMARY KEY,
					filename VARCHAR(255) NOT NULL,
					media_type VARCHAR(100) NOT NULL,
					status VARCHAR(20) NOT NULL,
					error_message VARCHAR(1000) NULL,
					char_count INT NULL,
					chunk_count INT NULL,
					original_path VARCHAR(1000) NOT NULL,
					text_path VARCHAR(1000) NULL,
					created_at DATETIME(3) NOT NULL
				) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
				""");
		jdbc.execute("""
				CREATE TABLE IF NOT EXISTS rag_chunk (
					id VARCHAR(36) NOT NULL PRIMARY KEY,
					document_id VARCHAR(36) NOT NULL,
					chunk_index INT NOT NULL,
					text_path VARCHAR(1000) NOT NULL,
					char_count INT NOT NULL,
					CONSTRAINT fk_rag_chunk_document FOREIGN KEY (document_id) REFERENCES rag_document (id) ON DELETE CASCADE
				) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
				""");
	}

}
