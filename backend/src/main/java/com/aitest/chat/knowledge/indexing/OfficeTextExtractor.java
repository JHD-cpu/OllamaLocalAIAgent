package com.aitest.chat.knowledge.indexing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/** 用 Apache POI 抽取 docx / xlsx 纯文本。 */
public final class OfficeTextExtractor {

	private OfficeTextExtractor() {
	}

	/** 抽取 Word（.docx）段落文本。 */
	public static String readDocx(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file); XWPFDocument doc = new XWPFDocument(in)) {
			StringBuilder text = new StringBuilder();
			for (XWPFParagraph paragraph : doc.getParagraphs()) {
				String line = paragraph.getText();
				if (line == null || line.isBlank()) {
					if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
						text.append('\n');
					}
					continue;
				}
				String style = paragraph.getStyle();
				boolean heading = style != null && style.toLowerCase().contains("heading");
				if (!text.isEmpty()) {
					text.append(heading ? "\n\n" : '\n');
				}
				if (heading && !line.trim().startsWith("#")) {
					text.append("# ");
				}
				text.append(line.trim());
			}
			return text.toString().trim();
		}
	}

	/** 抽取 Excel（.xlsx/.xls）各 sheet 单元格文本。 */
	public static String readSpreadsheet(Path file) throws IOException {
		DataFormatter formatter = new DataFormatter();
		try (InputStream in = Files.newInputStream(file); Workbook workbook = WorkbookFactory.create(in)) {
			StringBuilder text = new StringBuilder();
			for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
				Sheet sheet = workbook.getSheetAt(s);
				if (sheet == null) {
					continue;
				}
				if (!text.isEmpty()) {
					text.append("\n\n");
				}
				text.append("# ").append(sheet.getSheetName()).append('\n');
				for (Row row : sheet) {
					if (row == null) {
						continue;
					}
					StringBuilder line = new StringBuilder();
					for (Cell cell : row) {
						String value = formatter.formatCellValue(cell);
						if (value == null || value.isBlank()) {
							continue;
						}
						if (!line.isEmpty()) {
							line.append('\t');
						}
						line.append(value.trim());
					}
					if (!line.isEmpty()) {
						text.append(line).append('\n');
					}
				}
			}
			return text.toString().trim();
		}
	}

}
