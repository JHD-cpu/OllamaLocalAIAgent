package com.aitest.chat.knowledge.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 用固定向量代替嵌入模型，确认主题变化处会切开，同一主题会留在一块。 */
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

		List<String> together = SemanticChunker.split(text, sentences -> sentences.stream().map(sentence -> new float[] { 1, 0 }).toList(),
				90, 100);
		List<String> limited = SemanticChunker.split(text, sentences -> sentences.stream().map(sentence -> new float[] { 1, 0 }).toList(),
				90, 10);

		assertEquals(1, together.size());
		assertEquals(3, limited.size());
	}

	/** 天气句给一个方向，行情句给正交方向，这样中间那条边界距离最大。 */
	private static List<float[]> embed(List<String> sentences) {
		return sentences.stream().map(sentence -> sentence.contains("股价") || sentence.contains("指数") ? new float[] { 0, 1 }
				: new float[] { 1, 0 }).toList();
	}

}
