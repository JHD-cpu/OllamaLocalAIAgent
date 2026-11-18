package com.aitest.chat.knowledge.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlannedChunkerTest {

	@Test
	void splitsByTopicTreeLeaves() {
		String text = """
				张三
				电话 13800000000

				教育背景
				某某大学 计算机

				项目经历
				智能客服平台
				负责对话链路

				数据中台建设
				负责数仓建模

				专业技能
				Java Python
				""";
		DocumentChunkPlan plan = new DocumentChunkPlan(
			"resume",
			"张三简历",
			List.of(
				node("教育背景", "topic", "教育背景", List.of(
					node("某某大学", "subtopic", "某某大学 计算机", List.of())
				)),
				node("项目经历", "topic", "项目经历", List.of(
					node("智能客服平台", "subtopic", "智能客服平台", List.of(
						node("对话链路", "concept", "负责对话链路", List.of())
					)),
					node("数据中台建设", "subtopic", "数据中台建设", List.of(
						node("数仓建模", "concept", "负责数仓建模", List.of())
					))
				)),
				node("专业技能", "topic", "专业技能", List.of(
					node("技术栈", "concept", "Java Python", List.of())
				))
			),
			2200
		);
		List<String> chunks = PlannedChunker.split(text, plan, sentences -> List.of(), 95);
		assertTrue(chunks.size() >= 3);
		assertTrue(chunks.stream().anyMatch(c -> c.contains("【主题】") && c.contains("教育背景")));
		assertTrue(chunks.stream().anyMatch(c -> c.contains("【子主题】智能客服平台")));
		assertTrue(chunks.stream().anyMatch(c -> c.contains("【子主题】数据中台建设")));
		assertTrue(chunks.stream().anyMatch(c -> c.contains("专业技能")));
	}

	@Test
	void refineAnchorsDropsMissingLeaves() {
		DocumentChunkPlan plan = new DocumentChunkPlan(
			"resume",
			"",
			List.of(
				node("教育背景", "topic", "教育背景", List.of()),
				node("不存在", "topic", "完全不存在的锚点XYZ", List.of()),
				node("专业技能", "topic", "专业技能", List.of())
			),
			2000
		);
		DocumentChunkPlan refined = DocumentChunkPlanner.refineAnchors(plan, "教育背景\n...\n专业技能\nJava");
		assertEquals(2, refined.tree().size());
		assertEquals("教育背景", refined.tree().get(0).title());
		assertEquals("专业技能", refined.tree().get(1).title());
	}

	private static ChunkTreeNode node(String title, String level, String anchor, List<ChunkTreeNode> children) {
		return new ChunkTreeNode(title, anchor, level, children);
	}

}
