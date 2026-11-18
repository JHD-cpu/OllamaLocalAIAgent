package com.aitest.chat.knowledge.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CoarseTextSplitterTest {

	@Test
	void coversFullTextWithoutDropping() {
		StringBuilder raw = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			raw.append("段落").append(i).append('\n').append("内容".repeat(80)).append("\n\n");
		}
		String text = raw.toString();
		List<String> parts = CoarseTextSplitter.split(text, 3000);
		assertTrue(parts.size() >= 2);
		String joined = String.join("\n", parts).replace("\n", "");
		String compact = text.replace("\n", "").replace("\r", "");
		assertEquals(compact.length(), joined.length());
	}

}
