package com.aitest.chat.rag;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 从 PDF 抽出纯文本。某一页去掉空白后仍然很短，就当成扫描页，交给 OCR。
 */
public final class PdfTextExtractor {

	/** 识别一张页面图。测试可以放一个会失败的实现，确认文字层够用时不会调用它。 */
	public interface PageOcr {

		String read(BufferedImage image);

	}

	private PdfTextExtractor() {
	}

	public static String extract(Path file, int minChars, PageOcr ocr) throws IOException {
		try (PDDocument document = Loader.loadPDF(file.toFile())) {
			PDFTextStripper stripper = new PDFTextStripper();
			PDFRenderer renderer = new PDFRenderer(document);
			StringBuilder all = new StringBuilder();
			int pages = document.getNumberOfPages();
			for (int page = 1; page <= pages; page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				String text = stripper.getText(document);
				if (compactLength(text) < minChars) {
					BufferedImage image = renderer.renderImageWithDPI(page - 1, 200);
					text = ocr.read(image);
				}
				if (text != null && !text.isBlank()) {
					all.append(text.trim()).append('\n');
				}
			}
			return all.toString().trim();
		}
	}

	/** 不计空白，用来判断这一页是不是几乎没有文字层。 */
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

}
