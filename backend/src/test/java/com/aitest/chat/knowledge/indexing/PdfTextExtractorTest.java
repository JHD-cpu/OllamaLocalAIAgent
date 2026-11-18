package com.aitest.chat.knowledge.indexing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

/** 有文字层的 PDF 直接抽出文字，不调用 OCR。 */
class PdfTextExtractorTest {

	@Test
	void readsTextLayerWithoutOcr() throws Exception {
		Path file = Files.createTempFile("rag-page", ".pdf");
		try {
			try (PDDocument document = new PDDocument()) {
				PDPage page = new PDPage();
				document.addPage(page);
				try (PDPageContentStream content = new PDPageContentStream(document, page)) {
					content.beginText();
					content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					content.newLineAtOffset(50, 700);
					content.showText("This page has a real text layer for the extractor test.");
					content.endText();
				}
				document.save(file.toFile());
			}

			String text = PdfTextExtractor.extract(file, 20, image -> {
				throw new AssertionError("文字层足够时不应 OCR");
			});

			assertTrue(text.contains("real text layer"));
			assertFalse(text.isBlank());
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

}
