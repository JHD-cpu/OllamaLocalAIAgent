package com.aitest.chat.knowledge.indexing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.aitest.chat.knowledge.config.RagProperties;
import com.aitest.chat.knowledge.embedding.SettingsEmbeddingModel;

/**
 * 文本入库切分：先粗分片，再对多窗并行跑「主题树 + 细切」；
 * 失败时回退结构优先语义切分。支持删除时协作取消。
 */
@Component
public class EmbeddingSemanticChunker implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingSemanticChunker.class);

	private static final int PLAN_WINDOW_CHARS = 12000;

	/** 超过此长度跳过 LLM 主题树与嵌入断点，改用结构粗分（适合数百页手册）。 */
	private static final int HUGE_DOC_CHARS = 80_000;

	private static final int HUGE_WINDOW_CHARS = 24_000;

	private final SettingsEmbeddingModel embeddingModel;

	private final RagProperties properties;

	private final DocumentChunkPlanner planner;

	/** 单文档内粗分窗并行池（与文档级 indexer 叠加时控制 Ollama 压力）。 */
	private final ExecutorService windowWorkers = Executors.newFixedThreadPool(windowPoolSize(), task -> {
		Thread thread = new Thread(task, "knowledge-chunk-window");
		thread.setDaemon(true);
		return thread;
	});

	EmbeddingSemanticChunker(
		SettingsEmbeddingModel embeddingModel,
		RagProperties properties,
		DocumentChunkPlanner planner
	) {
		this.embeddingModel = embeddingModel;
		this.properties = properties;
		this.planner = planner;
	}

	public List<String> split(String text) {
		return split(null, text, IndexProgress.noop(), IndexGuard.noop());
	}

	public List<String> split(String filename, String text) {
		return split(filename, text, IndexProgress.noop(), IndexGuard.noop());
	}

	public List<String> split(String filename, String text, IndexProgress progress) {
		return split(filename, text, progress, IndexGuard.noop());
	}

	/** 先粗分片，再并行主题树细切；超长文档走快速结构分片。进度 40%–85%。 */
	public List<String> split(String filename, String text, IndexProgress progress, IndexGuard guard) {
		IndexGuard stop = guard == null ? IndexGuard.noop() : guard;
		IndexProgress report = stop.wrap(progress);
		stop.check();
		int chars = text == null ? 0 : text.length();
		int maxChars = this.properties.getChunkMaxChars();
		log.info("文本分片开始 file={} chars={} 默认上限={} windowPool={}", filename, chars, maxChars, windowPoolSize());
		report.update(42, "粗分片中");

		int packedMin = Math.min(1200, Math.max(600, maxChars / 3));
		if (chars >= HUGE_DOC_CHARS) {
			// 数百页文档：不做逐窗 LLM 主题树/嵌入断点，避免数小时卡住
			stop.check();
			report.update(50, "超长文档快速结构分片");
			List<String> raw = CoarseTextSplitter.split(text, Math.max(maxChars, HUGE_WINDOW_CHARS / 2));
			List<String> packed = SemanticChunker.pack(raw, maxChars, packedMin);
			log.info("超长文档快分完成 file={} chars={} chunks={}", filename, chars, packed.size());
			report.update(85, "分片完成，准备入库", packed.size());
			return packed;
		}

		List<String> windows = CoarseTextSplitter.split(text, PLAN_WINDOW_CHARS);
		log.info("粗分片完成 file={} windows={}", filename, windows.size());

		List<String> chunks;
		if (windows.size() <= 1) {
			chunks = new ArrayList<>();
			if (!windows.isEmpty()) {
				stop.check();
				report.update(45, "主题树分片 1/1", 0);
				chunks.addAll(splitWindow(filename, windows.get(0), maxChars, stop));
				report.update(83, "已完成分段 1/1", chunks.size());
			}
		}
		else {
			chunks = splitWindowsParallel(filename, windows, maxChars, stop, report);
		}

		stop.check();
		List<String> packed = SemanticChunker.pack(chunks, maxChars, packedMin);
		log.info("分片完成 file={} rawChunks={} packedChunks={} maxChars={}", filename, chunks.size(), packed.size(), maxChars);
		report.update(85, "分片完成，准备入库", packed.size());
		return packed;
	}

	private List<String> splitWindowsParallel(
		String filename,
		List<String> windows,
		int maxChars,
		IndexGuard stop,
		IndexProgress report
	) {
		AtomicInteger done = new AtomicInteger(0);
		AtomicInteger chunkCount = new AtomicInteger(0);
		List<CompletableFuture<ChunkWindowResult>> futures = new ArrayList<>(windows.size());
		for (int i = 0; i < windows.size(); i++) {
			final int index = i;
			final String window = windows.get(i);
			final String partName = filename + "#part" + (i + 1);
			futures.add(CompletableFuture.supplyAsync(() -> {
				stop.check();
				List<String> partChunks = splitWindow(partName, window, maxChars, stop);
				int finished = done.incrementAndGet();
				int totalChunks = chunkCount.addAndGet(partChunks.size());
				int pct = 45 + (int) Math.round(38.0 * finished / Math.max(1, windows.size()));
				report.update(Math.min(83, pct), "已完成分段 " + finished + "/" + windows.size(), totalChunks);
				return new ChunkWindowResult(index, partChunks);
			}, this.windowWorkers));
		}

		try {
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
		}
		catch (CompletionException ex) {
			for (CompletableFuture<ChunkWindowResult> future : futures) {
				future.cancel(true);
			}
			Throwable cause = unwrap(ex);
			if (cause instanceof IndexCancelledException cancelled) {
				throw cancelled;
			}
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("并行分片失败", cause);
		}

		stop.check();
		List<ChunkWindowResult> ordered = futures.stream()
			.map(CompletableFuture::join)
			.sorted(Comparator.comparingInt(ChunkWindowResult::index))
			.toList();
		List<String> chunks = new ArrayList<>();
		for (ChunkWindowResult result : ordered) {
			chunks.addAll(result.chunks());
		}
		return chunks;
	}

	private List<String> splitWindow(String filename, String text, int maxChars, IndexGuard stop) {
		stop.check();
		DocumentChunkPlan plan = this.planner.plan(filename, text, maxChars);
		stop.check();
		List<String> chunks;
		if (plan.hasTree() && !plan.flattenLeaves().isEmpty()) {
			log.info("按主题树分片 part={} leaves={} maxChars={}", filename, plan.flattenLeaves().size(), plan.maxChunkChars());
			chunks = PlannedChunker.split(text, plan, sentences -> embed(sentences, stop), this.properties.getBreakpointPercentile());
			if (chunks.isEmpty()) {
				log.info("主题树未切出内容，回退语义分片 part={}", filename);
				stop.check();
				chunks = SemanticChunker.split(text, sentences -> embed(sentences, stop), this.properties.getBreakpointPercentile(), plan.maxChunkChars());
			}
		}
		else {
			log.info("无主题树，回退语义分片 part={}", filename);
			chunks = SemanticChunker.split(text, sentences -> embed(sentences, stop), this.properties.getBreakpointPercentile(), plan.maxChunkChars());
		}
		stop.check();
		log.info("分段完成 part={} type={} chunks={}", filename, plan.docType(), chunks.size());
		return chunks;
	}

	private List<float[]> embed(List<String> sentences, IndexGuard stop) {
		List<float[]> vectors = new ArrayList<>();
		for (int start = 0; start < sentences.size(); start += 16) {
			stop.check();
			int end = Math.min(start + 16, sentences.size());
			log.info("嵌入句子 {}-{}/{}", start + 1, end, sentences.size());
			vectors.addAll(this.embeddingModel.embed(sentences.subList(start, end)));
		}
		return vectors;
	}

	@Override
	public void destroy() {
		this.windowWorkers.shutdownNow();
	}

	private static int windowPoolSize() {
		int cores = Runtime.getRuntime().availableProcessors();
		return Math.max(2, Math.min(3, cores));
	}

	private static Throwable unwrap(Throwable ex) {
		Throwable cur = ex;
		while (cur instanceof CompletionException && cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur == null ? ex : cur;
	}

	private record ChunkWindowResult(int index, List<String> chunks) {
		private ChunkWindowResult {
			chunks = chunks == null ? List.of() : List.copyOf(chunks);
		}
	}

}
