package com.aitest.chat.knowledge.storage;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 启动时创建 RAG 表并修复历史时间字段。 */
@Component
public class RagSchema {
   private static final Logger log = LoggerFactory.getLogger(RagSchema.class);
   private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

   /** 建表并迁移旧版毫秒时间戳。 */
   public RagSchema(JdbcTemplate jdbc) {
      jdbc.execute("PRAGMA foreign_keys = ON");
      jdbc.execute(
         "CREATE TABLE IF NOT EXISTS rag_document (\n\tid TEXT NOT NULL PRIMARY KEY,\n\tfilename TEXT NOT NULL,\n\tmedia_type TEXT NOT NULL,\n\tstatus TEXT NOT NULL,\n\terror_message TEXT NULL,\n\tchar_count INTEGER NULL,\n\tchunk_count INTEGER NULL,\n\tprogress INTEGER NULL,\n\tprogress_message TEXT NULL,\n\toriginal_path TEXT NOT NULL,\n\ttext_path TEXT NULL,\n\tcreated_at TEXT NOT NULL\n)\n"
      );
      ensureColumn(jdbc, "rag_document", "progress", "INTEGER");
      ensureColumn(jdbc, "rag_document", "progress_message", "TEXT");
      jdbc.execute(
         "CREATE TABLE IF NOT EXISTS rag_chunk (\n\tid TEXT NOT NULL PRIMARY KEY,\n\tdocument_id TEXT NOT NULL,\n\tchunk_index INTEGER NOT NULL,\n\ttext_path TEXT NOT NULL,\n\tchar_count INTEGER NOT NULL,\n\tFOREIGN KEY (document_id) REFERENCES rag_document (id) ON DELETE CASCADE\n)\n"
      );
      jdbc.execute(
         "CREATE TABLE IF NOT EXISTS chat_session (\n\tid TEXT NOT NULL PRIMARY KEY,\n\ttitle TEXT NOT NULL,\n\tupdated_at TEXT NOT NULL\n)\n"
      );
      jdbc.execute(
         "CREATE TABLE IF NOT EXISTS chat_message (\n\tid TEXT NOT NULL PRIMARY KEY,\n\tsession_id TEXT NOT NULL,\n\tord INTEGER NOT NULL,\n\trole TEXT NOT NULL,\n\tcontent TEXT NOT NULL,\n\tthinking TEXT NULL,\n\ttools_json TEXT NULL,\n\tsources_json TEXT NULL,\n\tmodel TEXT NULL,\n\timages_json TEXT NULL,\n\tcreated_at TEXT NOT NULL,\n\tFOREIGN KEY (session_id) REFERENCES chat_session (id) ON DELETE CASCADE\n)\n"
      );
      jdbc.execute(
         "CREATE TABLE IF NOT EXISTS memory_item (\n\tid TEXT NOT NULL PRIMARY KEY,\n\tcontent TEXT NOT NULL,\n\ttags TEXT NULL,\n\tcreated_at TEXT NOT NULL,\n\tupdated_at TEXT NOT NULL\n)\n"
      );
      repairCreatedAt(jdbc);
   }

   /** 兼容旧库：缺列则补齐。 */
   private static void ensureColumn(JdbcTemplate jdbc, String table, String column, String type) {
      List<String> names = jdbc.query("PRAGMA table_info(" + table + ")", (rs, row) -> rs.getString("name"));
      boolean exists = names.stream().anyMatch(name -> column.equalsIgnoreCase(name));
      if (!exists) {
         jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
         log.info("已为 {}.{} 增加列 {}", table, column, type);
      }
   }

   /** 将 created_at 从纯数字毫秒改为格式化字符串。 */
   private static void repairCreatedAt(JdbcTemplate jdbc) {
      List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, created_at FROM rag_document WHERE created_at GLOB '[0-9]*'");
      if (!rows.isEmpty()) {
         for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String raw = String.valueOf(row.get("created_at"));

            LocalDateTime time;
            try {
               time = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(raw)), ZoneId.systemDefault());
            } catch (RuntimeException var8) {
               time = LocalDateTime.now();
            }

            jdbc.update("UPDATE rag_document SET created_at = ? WHERE id = ?", new Object[]{time.format(CREATED_AT), id});
         }

         log.info("已修复知识库时间字段 {} 条", rows.size());
      }
   }
}
