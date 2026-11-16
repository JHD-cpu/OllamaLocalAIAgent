package com.aitest.chat.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

/** 把上传的 txt、md 或 pdf 变成一篇纯文本。 */
@Component
class DocumentTextReader {

	private final RagProperties properties;

	private final PaddlePageOcr ocr;

	DocumentTextReader(RagProperties properties, PaddlePageOcr ocr) {
		this.properties = properties;
		this.ocr = ocr;
	}

	String read(String filename, Path original) throws IOException {
		String extension = extension(filename);
		if ("pdf".equals(extension)) {
			return PdfTextExtractor.extract(original, this.properties.getOcrMinChars(), this.ocr);
		}
		String text = java.nio.file.Files.readString(original, StandardCharsets.UTF_8);
		if (text.startsWith("\uFEFF")) {
			text = text.substring(1);
		}
		return text.trim();
	}

	static String extension(String filename) {
		String name = filename == null ? "" : filename.trim().toLowerCase();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}

}
