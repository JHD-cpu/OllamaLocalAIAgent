package com.aitest.chat.memory;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.aitest.chat.memory.MemoryRepository.MemoryRow;

/** 长期记忆门面。 */
@Service
public class MemoryService {

	private final MemoryRepository repository;

	public MemoryService(MemoryRepository repository) {
		this.repository = repository;
	}

	public String save(String content, String tags) {
		if (content == null || content.isBlank()) {
			return "记忆内容不能为空。";
		}
		if (content.length() > 4000) {
			return "单条记忆不能超过 4000 字。";
		}
		MemoryRow row = this.repository.save(content, tags);
		return "已保存记忆 id=" + row.id();
	}

	public String search(String query) {
		List<MemoryRow> rows = this.repository.search(query, 8);
		if (rows.isEmpty()) {
			return "没有找到相关记忆。";
		}
		return rows.stream()
			.map(row -> "- [" + row.id() + "] " + row.content()
					+ (row.tags() == null || row.tags().isBlank() ? "" : "（标签：" + row.tags() + "）"))
			.collect(Collectors.joining("\n"));
	}

}
