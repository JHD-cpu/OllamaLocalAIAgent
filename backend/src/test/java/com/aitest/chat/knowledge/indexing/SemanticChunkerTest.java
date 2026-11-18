package com.aitest.chat.knowledge.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 结构优先切分：栏目/项目应整块保留，主题变化处才切开。 */
class SemanticChunkerTest {

	@Test
	void splitsWhereTopicChanges() {
		String text = "今天的天气非常好。很适合出门去散步。公司股价大幅下跌。市场指数继续下探。";

		List<String> chunks = SemanticChunker.split(text, SemanticChunkerTest::embed, 50, 1000);

		assertEquals(2, chunks.size());
		assertTrue(chunks.get(0).contains("天气"));
		assertTrue(chunks.get(0).contains("散步"));
		assertFalse(chunks.get(0).contains("股价"));
		assertTrue(chunks.get(1).contains("股价"));
		assertTrue(chunks.get(1).contains("指数"));
	}

	@Test
	void keepsSimilarSentencesTogetherUntilMaxLength() {
		String text = "甲甲甲甲甲甲甲。乙乙乙乙乙乙乙。丙丙丙丙丙丙丙。";

		List<String> together = SemanticChunker.split(text,
				sentences -> sentences.stream().map(sentence -> new float[] { 1, 0 }).toList(), 90, 100);
		List<String> limited = SemanticChunker.split(text,
				sentences -> sentences.stream().map(sentence -> new float[] { 1, 0 }).toList(), 90, 10);

		assertEquals(1, together.size());
		assertTrue(limited.size() >= 2);
	}

	@Test
	void keepsResumeProjectAsOneChunk() {
		String text = """
				项目经历
				智能客服平台 | 2021.03-2022.01
				负责对话流程设计与知识库接入。
				完成多轮问答与工单联动。
				技术栈：Java、Vue、Elasticsearch。

				数据中台 | 2022.02-2023.06
				搭建指标口径与数仓分层。
				推动报表自助化。
				""";

		List<String> chunks = SemanticChunker.split(text,
				sentences -> sentences.stream().map(sentence -> new float[] { 1, 0 }).toList(), 95, 2200);

		assertTrue(chunks.size() >= 2);
		assertTrue(chunks.stream().anyMatch(c -> c.contains("智能客服平台") && c.contains("知识库接入")
				&& c.contains("Elasticsearch")));
		assertTrue(chunks.stream().anyMatch(c -> c.contains("数据中台") && c.contains("数仓分层")));
		assertFalse(chunks.stream().anyMatch(c -> c.contains("智能客服平台") && c.contains("数据中台")));
	}

	@Test
	void detectsSectionHeaders() {
		assertTrue(SemanticChunker.isSectionHeader("项目经历"));
		assertTrue(SemanticChunker.isSectionHeader("智能客服平台 | 2021.03-2022.01"));
		assertTrue(SemanticChunker.isSectionHeader("## 教育背景"));
		assertFalse(SemanticChunker.isSectionHeader("负责对话流程设计与知识库接入。"));
	}

	private static List<float[]> embed(List<String> sentences) {
		return sentences.stream()
			.map(sentence -> sentence.contains("股价") || sentence.contains("指数") ? new float[] { 0, 1 }
					: new float[] { 1, 0 })
			.toList();
	}

}
