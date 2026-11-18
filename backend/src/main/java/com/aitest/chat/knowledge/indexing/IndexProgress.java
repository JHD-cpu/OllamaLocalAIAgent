package com.aitest.chat.knowledge.indexing;

/** 知识库入库进度回调：百分比、阶段文案、已产生分块数。 */
@FunctionalInterface
public interface IndexProgress {

	void update(int percent, String message, Integer chunkCount);

	static IndexProgress noop() {
		return (percent, message, chunkCount) -> {
		};
	}

	default void update(int percent, String message) {
		update(percent, message, null);
	}
}
