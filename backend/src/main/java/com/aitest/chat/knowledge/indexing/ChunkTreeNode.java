package com.aitest.chat.knowledge.indexing;

import java.util.List;

/** 主题树节点：主题 → 子主题 → 概念。 */
public record ChunkTreeNode(
	String title,
	String anchor,
	String level,
	List<ChunkTreeNode> children
) {
	public ChunkTreeNode {
		title = blank(title);
		anchor = blank(anchor);
		level = blank(level);
		children = children == null
			? List.of()
			: List.copyOf(children.stream().filter(n -> n != null && !n.title().isBlank()).toList());
	}

	public boolean hasChildren() {
		return !children.isEmpty();
	}

	private static String blank(String value) {
		return value == null ? "" : value.trim();
	}
}
