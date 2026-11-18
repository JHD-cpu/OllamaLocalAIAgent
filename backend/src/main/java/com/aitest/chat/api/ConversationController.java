package com.aitest.chat.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aitest.chat.conversation.ConversationRepository;
import com.aitest.chat.conversation.ConversationRepository.MessageInput;
import com.aitest.chat.conversation.ConversationRepository.MessageRow;
import com.aitest.chat.conversation.ConversationRepository.SessionRow;

import tools.jackson.databind.json.JsonMapper;

/** 多会话历史 CRUD API。 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

	private final ConversationRepository repository;

	private final JsonMapper jsonMapper;

	public ConversationController(ConversationRepository repository, JsonMapper jsonMapper) {
		this.repository = repository;
		this.jsonMapper = jsonMapper;
	}

	@GetMapping
	public List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (SessionRow row : this.repository.listSessions()) {
			out.add(sessionBody(row));
		}
		return out;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@RequestBody(required = false) CreateRequest request) {
		SessionRow row = this.repository.create(request == null ? null : request.title());
		return sessionBody(row);
	}

	@PatchMapping("/{id}")
	public Map<String, Object> rename(@PathVariable String id, @RequestBody RenameRequest request) {
		return this.repository.rename(id, request == null ? null : request.title())
			.map(this::sessionBody)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable String id) {
		if (!this.repository.delete(id)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
		}
	}

	@GetMapping("/{id}/messages")
	public Map<String, Object> messages(@PathVariable String id) {
		SessionRow session = this.repository.findSession(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
		List<Map<String, Object>> messages = new ArrayList<>();
		for (MessageRow row : this.repository.listMessages(id)) {
			messages.add(messageBody(row));
		}
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("session", sessionBody(session));
		body.put("messages", messages);
		return body;
	}

	@PutMapping("/{id}/messages")
	public Map<String, Object> replaceMessages(@PathVariable String id, @RequestBody ReplaceMessagesRequest request) {
		List<MessageInput> inputs = new ArrayList<>();
		if (request != null && request.messages() != null) {
			for (MessagePayload item : request.messages()) {
				if (item == null || item.role() == null || item.role().isBlank()) {
					continue;
				}
				inputs.add(new MessageInput(item.role().trim(), item.content(), item.thinking(),
						toJson(item.tools()), toJson(item.sources()), item.model(), toJson(item.images())));
			}
		}
		try {
			this.repository.replaceMessages(id, inputs);
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
		}
		return messages(id);
	}

	private Map<String, Object> sessionBody(SessionRow row) {
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("id", row.id());
		body.put("title", row.title());
		body.put("updatedAt", row.updatedAt());
		return body;
	}

	private Map<String, Object> messageBody(MessageRow row) {
		LinkedHashMap<String, Object> body = new LinkedHashMap<>();
		body.put("role", row.role());
		body.put("content", row.content() == null ? "" : row.content());
		body.put("thinking", row.thinking() == null ? "" : row.thinking());
		body.put("tools", fromJsonList(row.toolsJson()));
		body.put("sources", fromJsonList(row.sourcesJson()));
		body.put("model", row.model() == null ? "" : row.model());
		body.put("images", fromJsonList(row.imagesJson()));
		return body;
	}

	private String toJson(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return this.jsonMapper.writeValueAsString(value);
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private List<Object> fromJsonList(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		try {
			Object parsed = this.jsonMapper.readValue(json, Object.class);
			if (parsed instanceof List<?> list) {
				return (List<Object>) list;
			}
		}
		catch (RuntimeException ignored) {
			/* ignore corrupt */
		}
		return List.of();
	}

	public record CreateRequest(String title) {
	}

	public record RenameRequest(String title) {
	}

	public record ReplaceMessagesRequest(List<MessagePayload> messages) {
	}

	/** images 可为 data URL 字符串，或 {name,dataUrl} 对象（文档多模态）。 */
	public record MessagePayload(String role, String content, String thinking, List<String> tools,
			List<Map<String, Object>> sources, String model, List<Object> images) {
	}

}
