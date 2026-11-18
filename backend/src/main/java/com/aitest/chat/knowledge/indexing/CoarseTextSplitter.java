package com.aitest.chat.knowledge.indexing;

import java.util.ArrayList;
import java.util.List;

/**
 * 大文本先粗分片：按空行/标题边界打包，不丢内容、不因文件过大拒绝。
 */
public final class CoarseTextSplitter {

	private CoarseTextSplitter() {
	}

	/**
	 * @param targetChars 单段目标长度；过长段落仍会单独成段
	 */
	public static List<String> split(String text, int targetChars) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		String normalized = text.replace("\r\n", "\n").trim();
		int target = Math.max(2000, targetChars);
		if (normalized.length() <= target) {
			return List.of(normalized);
		}
		String[] parts = normalized.split("\n");
		List<String> segments = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String rawLine : parts) {
			String line = rawLine;
			String candidate = current.isEmpty() ? line : current + "\n" + line;
			if (!current.isEmpty() && candidate.length() > target && isBoundary(line)) {
				segments.add(current.toString().trim());
				current.setLength(0);
				current.append(line);
				continue;
			}
			if (candidate.length() > target * 2 && current.length() >= target / 2) {
				segments.add(current.toString().trim());
				current.setLength(0);
				current.append(line);
				continue;
			}
			if (current.isEmpty()) {
				current.append(line);
			}
			else {
				current.append('\n').append(line);
			}
		}
		if (!current.isEmpty()) {
			segments.add(current.toString().trim());
		}
		return segments.stream().filter(s -> !s.isBlank()).toList();
	}

	private static boolean isBoundary(String line) {
		String t = line == null ? "" : line.trim();
		if (t.isEmpty()) {
			return true;
		}
		if (t.startsWith("#")) {
			return true;
		}
		if (t.length() <= 40 && (t.contains("经历") || t.contains("背景") || t.contains("技能") || t.contains("章节")
			|| t.contains("第") && (t.contains("章") || t.contains("节")))) {
			return true;
		}
		return false;
	}

}
