package com.aitest.chat.knowledge.indexing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LlmPageOcrTest {

	@Test
	void detectsVisionModelNames() {
		assertTrue(LlmPageOcr.looksVision("llava:7b", ""));
		assertTrue(LlmPageOcr.looksVision("qwen2.5-vl:7b", ""));
		assertTrue(LlmPageOcr.looksVision("minicpm-v", ""));
		assertTrue(LlmPageOcr.looksVision("foo", "qwen2vl"));
		assertFalse(LlmPageOcr.looksVision("qwen3.5:9b", "qwen3"));
		assertFalse(LlmPageOcr.looksVision("bge-m3", ""));
	}

}
