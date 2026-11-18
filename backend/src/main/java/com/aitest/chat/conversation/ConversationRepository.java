package com.aitest.chat.conversation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 会话与消息的 SQLite 持久化。 */
@Repository
public class ConversationRepository {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

	private final JdbcClient jdbc;

	public ConversationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 新建空会话。 */
	public SessionRow create(String title) {
		String id = UUID.randomUUID().toString();
		String now = LocalDateTime.now().format(TIME);
		String safeTitle = blankToDefault(title, "新对话");
		this.jdbc.sql("INSERT INTO chat_session (id, title, updated_at) VALUES (:id, :title, :updatedAt)")
			.param("id", id)
			.param("title", safeTitle)
			.param("updatedAt", now)
			.update();
		return new SessionRow(id, safeTitle, now);
	}

	/** 按更新时间倒序列出会话。 */
	public List<SessionRow> listSessions() {
		return this.jdbc.sql("SELECT id, title, updated_at FROM chat_session ORDER BY updated_at DESC")
			.query((rs, i) -> new SessionRow(rs.getString("id"), rs.getString("title"), rs.getString("updated_at")))
			.list();
	}

	public Optional<SessionRow> findSession(String id) {
		return this.jdbc.sql("SELECT id, title, updated_at FROM chat_session WHERE id = :id")
			.param("id", id)
			.query((rs, i) -> new SessionRow(rs.getString("id"), rs.getString("title"), rs.getString("updated_at")))
			.optional();
	}

	/** 重命名会话并刷新 updated_at。 */
	public Optional<SessionRow> rename(String id, String title) {
		String now = LocalDateTime.now().format(TIME);
		String safeTitle = blankToDefault(title, "新对话");
		int updated = this.jdbc.sql("UPDATE chat_session SET title = :title, updated_at = :updatedAt WHERE id = :id")
			.param("title", safeTitle)
			.param("updatedAt", now)
			.param("id", id)
			.update();
		if (updated == 0) {
			return Optional.empty();
		}
		return Optional.of(new SessionRow(id, safeTitle, now));
	}

	/** 删除会话（级联消息）。 */
	public boolean delete(String id) {
		return this.jdbc.sql("DELETE FROM chat_session WHERE id = :id").param("id", id).update() > 0;
	}

	/** 读取会话全部消息（按序）。 */
	public List<MessageRow> listMessages(String sessionId) {
		return this.jdbc.sql(
				"SELECT id, session_id, ord, role, content, thinking, tools_json, sources_json, model, images_json, created_at "
						+ "FROM chat_message WHERE session_id = :sessionId ORDER BY ord ASC")
			.param("sessionId", sessionId)
			.query((rs, i) -> new MessageRow(rs.getString("id"), rs.getString("session_id"), rs.getInt("ord"),
					rs.getString("role"), rs.getString("content"), rs.getString("thinking"), rs.getString("tools_json"),
					rs.getString("sources_json"), rs.getString("model"), rs.getString("images_json"),
					rs.getString("created_at")))
			.list();
	}

	/** 用全量消息覆盖会话（事务式：先删后插）。 */
	public void replaceMessages(String sessionId, List<MessageInput> messages) {
		if (findSession(sessionId).isEmpty()) {
			throw new IllegalArgumentException("会话不存在");
		}
		this.jdbc.sql("DELETE FROM chat_message WHERE session_id = :sessionId").param("sessionId", sessionId).update();
		String now = LocalDateTime.now().format(TIME);
		int ord = 0;
		for (MessageInput message : messages == null ? List.<MessageInput>of() : messages) {
			this.jdbc.sql(
					"INSERT INTO chat_message (id, session_id, ord, role, content, thinking, tools_json, sources_json, model, images_json, created_at) "
							+ "VALUES (:id, :sessionId, :ord, :role, :content, :thinking, :toolsJson, :sourcesJson, :model, :imagesJson, :createdAt)")
				.param("id", UUID.randomUUID().toString())
				.param("sessionId", sessionId)
				.param("ord", ord++)
				.param("role", message.role())
				.param("content", message.content() == null ? "" : message.content())
				.param("thinking", message.thinking())
				.param("toolsJson", message.toolsJson())
				.param("sourcesJson", message.sourcesJson())
				.param("model", message.model())
				.param("imagesJson", message.imagesJson())
				.param("createdAt", now)
				.update();
		}
		String title = deriveTitle(messages);
		this.jdbc.sql("UPDATE chat_session SET title = :title, updated_at = :updatedAt WHERE id = :id")
			.param("title", title)
			.param("updatedAt", now)
			.param("id", sessionId)
			.update();
	}

	private static String deriveTitle(List<MessageInput> messages) {
		if (messages == null) {
			return "新对话";
		}
		for (MessageInput message : messages) {
			if (message != null && "user".equals(message.role()) && message.content() != null
					&& !message.content().isBlank()) {
				String text = message.content().trim().replace('\n', ' ');
				return text.length() > 40 ? text.substring(0, 40) + "…" : text;
			}
		}
		return "新对话";
	}

	private static String blankToDefault(String value, String fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		String trimmed = value.trim();
		return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
	}

	public record SessionRow(String id, String title, String updatedAt) {
	}

	public record MessageRow(String id, String sessionId, int ord, String role, String content, String thinking,
			String toolsJson, String sourcesJson, String model, String imagesJson, String createdAt) {
	}

	public record MessageInput(String role, String content, String thinking, String toolsJson, String sourcesJson,
			String model, String imagesJson) {
	}

}
