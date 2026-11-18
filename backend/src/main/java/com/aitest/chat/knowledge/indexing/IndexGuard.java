package com.aitest.chat.knowledge.indexing;

/** 入库过程取消探测；已取消时抛 {@link IndexCancelledException}。 */
@FunctionalInterface
public interface IndexGuard {

	void check();

	static IndexGuard noop() {
		return () -> {
		};
	}

	default IndexProgress wrap(IndexProgress progress) {
		IndexProgress target = progress == null ? IndexProgress.noop() : progress;
		return (percent, message, chunkCount) -> {
			check();
			target.update(percent, message, chunkCount);
		};
	}
}
