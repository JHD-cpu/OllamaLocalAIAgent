package com.aitest.chat.knowledge.indexing;

import java.util.ArrayList;
import java.util.List;

/** LLM 输出的主题树与分片参数。 */
public record DocumentChunkPlan(
	String docType,
	String docTitle,
	List<ChunkTreeNode> tree,
	int maxChunkChars
) {
	public DocumentChunkPlan {
		docType = blankTo(docType, "general");
		docTitle = blankTo(docTitle, "");
		tree = tree == null ? List.of() : List.copyOf(tree);
		maxChunkChars = maxChunkChars <= 0 ? 4000 : Math.clamp(maxChunkChars, 1200, 8000);
	}

	public static DocumentChunkPlan fallback(int maxChunkChars) {
		return new DocumentChunkPlan("general", "", List.of(), maxChunkChars);
	}

	public boolean hasTree() {
		return !tree.isEmpty();
	}

	/** 按文档顺序展平为可切分裂（优先概念叶；无子节点则用当前层）。 */
	public List<SplitPoint> flattenLeaves() {
		List<SplitPoint> points = new ArrayList<>();
		walk(tree, "", "", "", points);
		return points;
	}

	private static void walk(
		List<ChunkTreeNode> nodes,
		String topic,
		String subtopic,
		String concept,
		List<SplitPoint> out
	) {
		for (ChunkTreeNode node : nodes) {
			if (node == null || node.title().isBlank()) {
				continue;
			}
			String level = node.level().isBlank() ? inferLevel(topic, subtopic, concept) : node.level().toLowerCase();
			String nextTopic = topic;
			String nextSub = subtopic;
			String nextConcept = concept;
			switch (level) {
				case "subtopic", "子主题" -> nextSub = node.title();
				case "concept", "概念" -> nextConcept = node.title();
				default -> {
					if (topic.isBlank()) {
						nextTopic = node.title();
					}
					else if (subtopic.isBlank()) {
						nextSub = node.title();
					}
					else {
						nextConcept = node.title();
					}
				}
			}
			if (node.hasChildren()) {
				walk(node.children(), nextTopic, nextSub, nextConcept, out);
			}
			else {
				String anchor = !node.anchor().isBlank() ? node.anchor() : node.title();
				out.add(new SplitPoint(nextTopic, nextSub, nextConcept, node.title(), anchor));
			}
		}
	}

	private static String inferLevel(String topic, String subtopic, String concept) {
		if (topic.isBlank()) {
			return "topic";
		}
		if (subtopic.isBlank()) {
			return "subtopic";
		}
		return "concept";
	}

	private static String blankTo(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	/** 一个切分点：对应主题路径 + 正文锚点。 */
	public record SplitPoint(
		String topic,
		String subtopic,
		String concept,
		String title,
		String anchor
	) {
		public String pathLabel() {
			List<String> parts = new ArrayList<>();
			if (topic != null && !topic.isBlank()) {
				parts.add(topic);
			}
			if (subtopic != null && !subtopic.isBlank()) {
				parts.add(subtopic);
			}
			if (concept != null && !concept.isBlank()) {
				parts.add(concept);
			}
			if (parts.isEmpty() && title != null && !title.isBlank()) {
				parts.add(title);
			}
			return String.join(" / ", parts);
		}

		public String header() {
			StringBuilder sb = new StringBuilder();
			if (topic != null && !topic.isBlank()) {
				sb.append("【主题】").append(topic).append('\n');
			}
			if (subtopic != null && !subtopic.isBlank()) {
				sb.append("【子主题】").append(subtopic).append('\n');
			}
			if (concept != null && !concept.isBlank()) {
				sb.append("【概念】").append(concept).append('\n');
			}
			else if (title != null && !title.isBlank() && (topic == null || !title.equals(topic))
				&& (subtopic == null || !title.equals(subtopic))) {
				sb.append("【概念】").append(title).append('\n');
			}
			return sb.toString();
		}
	}
}
