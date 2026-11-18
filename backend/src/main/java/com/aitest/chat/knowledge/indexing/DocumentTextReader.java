package com.aitest.chat.knowledge.indexing;


import com.aitest.chat.knowledge.config.RagProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 按扩展名读取上传原件为纯文本。 */
@Component
public class DocumentTextReader {
   private static final Logger log = LoggerFactory.getLogger(DocumentTextReader.class);
   private final RagProperties properties;
   private final PaddlePageOcr ocr;

   /** 注入 RAG 配置与 OCR 组件。 */
   DocumentTextReader(RagProperties properties, PaddlePageOcr ocr) {
      this.properties = properties;
      this.ocr = ocr;
   }

   /** 读取 pdf / txt / md 原件内容。 */
   public String read(String filename, Path original) throws IOException {
      String extension = extension(filename);
      if ("pdf".equals(extension)) {
         return PdfTextExtractor.extract(original, this.properties.getOcrMinChars(), this.ocr);
      } else {
         String text = Files.readString(original, StandardCharsets.UTF_8);
         if (text.startsWith("\ufeff")) {
            text = text.substring(1);
         }

         String trimmed = text.trim();
         log.info("读取文本 file={} chars={}", filename, trimmed.length());
         return trimmed;
      }
   }

   /** 从文件名解析小写扩展名。 */
   public static String extension(String filename) {
      String name = filename == null ? "" : filename.trim().toLowerCase();
      int dot = name.lastIndexOf(46);
      return dot < 0 ? "" : name.substring(dot + 1);
   }
}
