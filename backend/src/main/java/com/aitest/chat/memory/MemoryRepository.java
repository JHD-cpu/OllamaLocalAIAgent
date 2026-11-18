package com.aitest.chat.memory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 长期记忆 SQLite 仓储。 */
@Repository
public class MemoryRepository {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

	private final JdbcClient jdbc;

	public MemoryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public MemoryRow save(String content, String tags) {
		String id = UUID.randomUUID().toString();
		String now = LocalDateTime.now().format(TIME);
		String text = content == null ? "" : content.trim();
		String tagText = tags == null ? "" : tags.trim();
		this.jdbc.sql(
				"INSERT INTO memory_item (id, content, tags, created_at, updated_at) VALUES (:id, :content, :tags, :createdAt, :updatedAt)")
			.param("id", id)
			.param("content", text)
			.param("tags", tagText)
			.param("createdAt", now)
			.param("updatedAt", now)
			.update();
		return new MemoryRow(id, text, tagText, now, now);
	}

	public List<MemoryRow> search(String query, int limit) {
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		int cap = Math.max(1, Math.min(limit, 20));
		if (needle.isEmpty()) {
			return this.jdbc.sql(
					"SELECT id, content, tags, created_at, updated_at FROM memory_item ORDER BY updated_at DESC LIMIT :limit")
				.param("limit", cap)
				.query(this::map)
				.list();
		}
		return this.jdbc.sql(
				"SELECT id, content, tags, created_at, updated_at FROM memory_item "
						+ "WHERE lower(content) LIKE :q OR lower(ifnull(tags,'')) LIKE :q "
						+ "ORDER BY updated_at DESC LIMIT :limit")
			.param("q", "%" + needle + "%")
			.param("limit", cap)
			.query(this::map)
			.list();
	}

	private MemoryRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
		return new MemoryRow(rs.getString("id"), rs.getString("content"), rs.getString("tags"),
				rs.getString("created_at"), rs.getString("updated_at"));
	}

	public record MemoryRow(String id, String content, String tags, String createdAt, String updatedAt) {
	}

}
