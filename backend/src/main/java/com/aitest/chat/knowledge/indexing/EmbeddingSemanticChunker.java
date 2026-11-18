package com.aitest.chat.knowledge.indexing;



import com.aitest.chat.knowledge.embedding.SettingsEmbeddingModel;
import com.aitest.chat.knowledge.config.RagProperties;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 使用嵌入模型驱动 {@link SemanticChunker} 切分文档。 */
@Component
public class EmbeddingSemanticChunker {
   private static final Logger log = LoggerFactory.getLogger(EmbeddingSemanticChunker.class);
   private static final int BATCH = 16;
   private final SettingsEmbeddingModel embeddingModel;
   private final RagProperties properties;

   /** 注入嵌入模型与 RAG 切分参数。 */
   EmbeddingSemanticChunker(SettingsEmbeddingModel embeddingModel, RagProperties properties) {
      this.embeddingModel = embeddingModel;
      this.properties = properties;
   }

   /** 对全文做语义分块。 */
   public List<String> split(String text) {
      int chars = text == null ? 0 : text.length();
      log.info("语义切分 文本{}字 百分位={} 单块上限={}字", new Object[]{chars, this.properties.getBreakpointPercentile(), this.properties.getChunkMaxChars()});
      List<String> chunks = SemanticChunker.split(text, this::embed, this.properties.getBreakpointPercentile(), this.properties.getChunkMaxChars());
      log.info("语义切分完成 chunks={}", chunks.size());
      return chunks;
   }

   /** 分批嵌入句子列表。 */
   private List<float[]> embed(List<String> sentences) {
      List<float[]> vectors = new ArrayList<>();

      for (int start = 0; start < sentences.size(); start += 16) {
         int end = Math.min(start + 16, sentences.size());
         log.info("嵌入句子 {}-{}/{}", new Object[]{start + 1, end, sentences.size()});
         vectors.addAll(this.embeddingModel.embed(sentences.subList(start, end)));
      }

      return vectors;
   }
}
