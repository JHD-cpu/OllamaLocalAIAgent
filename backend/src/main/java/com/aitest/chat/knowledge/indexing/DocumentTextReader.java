package com.aitest.chat.knowledge.indexing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.aitest.chat.knowledge.config.RagProperties;

/** 按扩展名读取上传原件为纯文本。 */
@Component
public class DocumentTextReader {

	private static final Logger log = LoggerFactory.getLogger(DocumentTextReader.class);

	private final RagProperties properties;

	private final LlmPageOcr ocr;

	DocumentTextReader(RagProperties properties, LlmPageOcr ocr) {
		this.properties = properties;
		this.ocr = ocr;
	}

	public String read(String filename, Path original) throws IOException {
		return read(filename, original, IndexProgress.noop(), IndexGuard.noop());
	}

	public String read(String filename, Path original, IndexProgress progress) throws IOException {
		return read(filename, original, progress, IndexGuard.noop());
	}

	/** 读取原件并回报解析进度；支持取消。 */
	public String read(String filename, Path original, IndexProgress progress, IndexGuard guard) throws IOException {
		IndexGuard stop = guard == null ? IndexGuard.noop() : guard;
		IndexProgress report = stop.wrap(progress);
		stop.check();
		String extension = extension(filename);
		String trimmed;
		if ("pdf".equals(extension)) {
			trimmed = PdfTextExtractor.extract(original, this.properties.getOcrMinChars(), this.ocr, report, stop);
		}
		else if ("docx".equals(extension)) {
			report.update(10, "解析 Word");
			stop.check();
			trimmed = OfficeTextExtractor.readDocx(original);
			report.update(40, "文字提取完成");
		}
		else if ("xlsx".equals(extension) || "xls".equals(extension)) {
			report.update(10, "解析表格");
			stop.check();
			trimmed = OfficeTextExtractor.readSpreadsheet(original);
			report.update(40, "文字提取完成");
		}
		else {
			report.update(10, "读取文本");
			stop.check();
			String text = Files.readString(original, StandardCharsets.UTF_8);
			if (text.startsWith("\ufeff")) {
				text = text.substring(1);
			}
			trimmed = text.trim();
			report.update(40, "文字提取完成");
		}
		stop.check();
		log.info("读取文本 file={} chars={}", filename, trimmed.length());
		return trimmed;
	}

	public static String extension(String filename) {
		String name = filename == null ? "" : filename.trim().toLowerCase();
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1);
	}
}
