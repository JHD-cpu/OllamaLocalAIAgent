package com.aitest.chat.knowledge.indexing;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** PDF 逐页提取文字层，不足时调用 OCR。 */
public final class PdfTextExtractor {
   private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

   /** 工具类，禁止实例化。 */
   private PdfTextExtractor() {
   }

   /** 读取 PDF 全文；文字层过短时对单页 OCR。 */
   public static String extract(Path file, int minChars, PdfTextExtractor.PageOcr ocr) throws IOException {
      PDDocument document = Loader.loadPDF(file.toFile());

      String text;
      try {
         PDFTextStripper stripper = new PDFTextStripper();
         PDFRenderer renderer = new PDFRenderer(document);
         StringBuilder all = new StringBuilder();
         int pages = document.getNumberOfPages();
         int ocrPages = 0;
         log.info("开始读取 PDF pages={} 文字少于此值则 OCR={}", pages, minChars);

         for (int page = 1; page <= pages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            text = stripper.getText(document);
            int layerChars = compactLength(text);
            if (layerChars < minChars) {
               ocrPages++;
               log.info("第 {}/{} 页文字层 {} 字，改用 PaddleOCR", new Object[]{page, pages, layerChars});
               BufferedImage image = renderer.renderImageWithDPI(page - 1, 200.0F);
               text = ocr.read(image);
               log.info("第 {}/{} 页 OCR 完成 chars={}", new Object[]{page, pages, text == null ? 0 : text.trim().length()});
            } else {
               log.info("第 {}/{} 页使用文字层 chars={}", new Object[]{page, pages, layerChars});
            }

            if (text != null && !text.isBlank()) {
               all.append(text.trim()).append('\n');
            }
         }

         String result = all.toString().trim();
         log.info("PDF 读取完成 pages={} ocrPages={} chars={}", new Object[]{pages, ocrPages, result.length()});
         text = result;
      } catch (Throwable var14) {
         if (document != null) {
            try {
               document.close();
            } catch (Throwable var13) {
               var14.addSuppressed(var13);
            }
         }

         throw var14;
      }

      if (document != null) {
         document.close();
      }

      return text;
   }

   /** 统计非空白字符个数。 */
   private static int compactLength(String text) {
      if (text != null && !text.isBlank()) {
         int count = 0;

         for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
               count++;
            }
         }

         return count;
      } else {
         return 0;
      }
   }

   /** 单页图像 OCR 回调。 */
   public interface PageOcr {
      /** 识别一页图像中的文字。 */
      String read(BufferedImage image);
   }
}
