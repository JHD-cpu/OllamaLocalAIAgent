package com.aitest.chat.knowledge.indexing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PDF 提取：文字层充足则整本一次抽取；否则逐页处理，OCR 页有限并行。
 */
public final class PdfTextExtractor {

	private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

	/** 渲染 DPI：兼顾识别与 400+ 页扫描速度。 */
	private static final float OCR_DPI = 144.0F;

	private static final int OCR_PARALLEL = 2;

	private static final int OCR_BATCH = 4;

	private PdfTextExtractor() {
	}

	public static String extract(Path file, int minChars, PageOcr ocr) throws IOException {
		return extract(file, minChars, ocr, IndexProgress.noop(), IndexGuard.noop());
	}

	public static String extract(Path file, int minChars, PageOcr ocr, IndexProgress progress) throws IOException {
		return extract(file, minChars, ocr, progress, IndexGuard.noop());
	}

	/** 读取 PDF 全文；每批前检查取消。进度映射到 5%–40%。 */
	public static String extract(
		Path file,
		int minChars,
		PageOcr ocr,
		IndexProgress progress,
		IndexGuard guard
	) throws IOException {
		IndexProgress report = progress == null ? IndexProgress.noop() : progress;
		IndexGuard stop = guard == null ? IndexGuard.noop() : guard;
		PDDocument document = Loader.loadPDF(file.toFile());
		try {
			int pages = document.getNumberOfPages();
			stop.check();
			report.update(5, "解析 PDF（共 " + pages + " 页）");
			log.info("开始读取 PDF pages={} 文字少于此值则 OCR={}", pages, minChars);

			PDFTextStripper stripper = new PDFTextStripper();
			String whole = stripper.getText(document);
			int wholeCompact = compactLength(whole);
			int enough = Math.max(minChars * Math.max(1, pages / 3), pages * 40);
			if (wholeCompact >= enough) {
				log.info("PDF 文字层充足，整本一次提取 pages={} compactChars={} threshold={}", pages, wholeCompact, enough);
				stop.check();
				report.update(40, "文字提取完成");
				return whole.trim();
			}

			log.info("PDF 文字层偏少 compactChars={} threshold={}，改为逐页（必要时 OCR）", wholeCompact, enough);
			return extractPageWise(document, pages, minChars, ocr, report, stop);
		}
		finally {
			document.close();
		}
	}

	private static String extractPageWise(
		PDDocument document,
		int pages,
		int minChars,
		PageOcr ocr,
		IndexProgress report,
		IndexGuard stop
	) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		PDFRenderer renderer = new PDFRenderer(document);
		String[] pageTexts = new String[pages];
		List<Integer> blankOrScanPages = new ArrayList<>();
		List<Integer> weakTextPages = new ArrayList<>();

		for (int page = 1; page <= pages; page++) {
			stop.check();
			stripper.setStartPage(page);
			stripper.setEndPage(page);
			String text = stripper.getText(document);
			int layerChars = compactLength(text);
			if (layerChars >= minChars) {
				pageTexts[page - 1] = text == null ? "" : text.trim();
			}
			else {
				pageTexts[page - 1] = "";
				if (layerChars == 0) {
					blankOrScanPages.add(page);
				}
				else {
					weakTextPages.add(page);
				}
			}
			if (page == 1 || page % 25 == 0 || page == pages) {
				int pct = 5 + (int) Math.round(12.0 * page / Math.max(1, pages));
				report.update(pct, "扫描文字层 " + page + "/" + pages);
			}
		}

		int sparse = blankOrScanPages.size() + weakTextPages.size();
		boolean mostlyScanned = sparse * 2 >= pages;
		List<Integer> ocrPages = new ArrayList<>(weakTextPages);
		if (mostlyScanned) {
			ocrPages.addAll(blankOrScanPages);
		}
		else {
			log.info("非扫描主导：跳过空页 OCR blank={} weak={}", blankOrScanPages.size(), weakTextPages.size());
		}

		if (!ocrPages.isEmpty()) {
			log.info("开始并行 OCR pages={} parallel={} dpi={} mostlyScanned={}",
				ocrPages.size(), OCR_PARALLEL, OCR_DPI, mostlyScanned);
			runOcrBatches(renderer, pageTexts, ocrPages, ocr, report, stop, pages);
		}

		stop.check();
		StringBuilder all = new StringBuilder();
		for (String text : pageTexts) {
			if (text != null && !text.isBlank()) {
				all.append(text.trim()).append('\n');
			}
		}
		String result = all.toString().trim();
		log.info("PDF 读取完成 pages={} ocrPages={} chars={}", pages, ocrPages.size(), result.length());
		report.update(40, "文字提取完成");
		return result;
	}

	private static void runOcrBatches(
		PDFRenderer renderer,
		String[] pageTexts,
		List<Integer> ocrPages,
		PageOcr ocr,
		IndexProgress report,
		IndexGuard stop,
		int totalPages
	) {
		ExecutorService pool = Executors.newFixedThreadPool(OCR_PARALLEL, task -> {
			Thread thread = new Thread(task, "pdf-ocr");
			thread.setDaemon(true);
			return thread;
		});
		AtomicInteger finished = new AtomicInteger(0);
		try {
			for (int start = 0; start < ocrPages.size(); start += OCR_BATCH) {
				stop.check();
				int end = Math.min(start + OCR_BATCH, ocrPages.size());
				List<PageImage> batch = new ArrayList<>(end - start);
				for (int i = start; i < end; i++) {
					stop.check();
					int page = ocrPages.get(i);
					try {
						BufferedImage image = renderer.renderImageWithDPI(page - 1, OCR_DPI);
						batch.add(new PageImage(page, image));
					}
					catch (IOException ex) {
						throw new IllegalStateException("渲染 PDF 第 " + page + " 页失败", ex);
					}
				}

				List<CompletableFuture<PageText>> futures = new ArrayList<>(batch.size());
				for (PageImage item : batch) {
					futures.add(CompletableFuture.supplyAsync(() -> {
						stop.check();
						String text = ocr.read(item.image());
						int done = finished.incrementAndGet();
						int pct = 17 + (int) Math.round(22.0 * done / Math.max(1, ocrPages.size()));
						report.update(Math.min(39, pct), "OCR " + done + "/" + ocrPages.size() + "（总页 " + totalPages + "）");
						return new PageText(item.page(), text == null ? "" : text.trim());
					}, pool));
				}

				try {
					CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
				}
				catch (CompletionException ex) {
					for (CompletableFuture<PageText> future : futures) {
						future.cancel(true);
					}
					Throwable cause = unwrap(ex);
					if (cause instanceof IndexCancelledException cancelled) {
						throw cancelled;
					}
					if (cause instanceof RuntimeException runtime) {
						throw runtime;
					}
					throw new IllegalStateException("并行 OCR 失败", cause);
				}

				for (CompletableFuture<PageText> future : futures) {
					PageText pageText = future.join();
					pageTexts[pageText.page() - 1] = pageText.text();
				}
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static int compactLength(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < text.length(); i++) {
			if (!Character.isWhitespace(text.charAt(i))) {
				count++;
			}
		}
		return count;
	}

	private static Throwable unwrap(Throwable ex) {
		Throwable cur = ex;
		while (cur instanceof CompletionException && cur.getCause() != null) {
			cur = cur.getCause();
		}
		return cur == null ? ex : cur;
	}

	private record PageImage(int page, BufferedImage image) {
	}

	private record PageText(int page, String text) {
	}

	public interface PageOcr {
		String read(BufferedImage image);
	}
}
