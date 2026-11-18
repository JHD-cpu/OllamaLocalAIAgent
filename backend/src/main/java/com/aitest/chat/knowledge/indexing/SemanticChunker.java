package com.aitest.chat.knowledge.indexing;

import java.util.ArrayList;
import java.util.List;

/** 按句嵌入相似度做语义分块（无 Spring 依赖）。 */
public final class SemanticChunker {
   /** 工具类，禁止实例化。 */
   private SemanticChunker() {
   }

   /** 将全文切成语义连贯的文本块。 */
   public static List<String> split(String text, SemanticChunker.SentenceEmbedder embedder, int breakpointPercentile, int maxChars) {
      if (breakpointPercentile >= 1 && breakpointPercentile <= 99 && maxChars >= 1) {
         List<String> sentences = sentences(text, maxChars);
         if (sentences.isEmpty()) {
            return List.of();
         } else if (sentences.size() == 1) {
            return List.of(sentences.get(0).trim());
         } else {
            List<float[]> vectors = embedder.embed(sentences);
            if (vectors.size() != sentences.size()) {
               throw new IllegalStateException("嵌入条数和句子数不一致");
            } else {
               double[] distances = new double[sentences.size() - 1];
               List<Double> distanceList = new ArrayList<>();

               for (int i = 0; i < distances.length; i++) {
                  distances[i] = 1.0 - cosine(vectors.get(i), vectors.get(i + 1));
                  distanceList.add(distances[i]);
               }

               double threshold = distances.length == 1 ? 0.5 : percentile(distanceList, breakpointPercentile);
               List<String> chunks = new ArrayList<>();
               StringBuilder current = new StringBuilder(sentences.get(0));

               for (int i = 1; i < sentences.size(); i++) {
                  String next = sentences.get(i);
                  boolean topicChanged = distances[i - 1] > threshold;
                  boolean tooLong = current.length() + next.length() > maxChars;
                  if (!topicChanged && !tooLong) {
                     current.append(next);
                  } else {
                     String chunk = current.toString().trim();
                     if (!chunk.isEmpty()) {
                        chunks.add(chunk);
                     }

                     current = new StringBuilder(next);
                  }
               }

               String tail = current.toString().trim();
               if (!tail.isEmpty()) {
                  chunks.add(tail);
               }

               return chunks;
            }
         }
      } else {
         throw new IllegalArgumentException("语义切分参数不正确");
      }
   }

   /** 按标点拆句并限制单句长度。 */
   static List<String> sentences(String text, int maxChars) {
      String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
      if (normalized.isEmpty()) {
         return List.of();
      } else {
         String[] parts = normalized.split("(?<=[。！？!?；;\\n])");
         List<String> sentences = new ArrayList<>();

         for (String part : parts) {
            if (!part.isBlank()) {
               if (sentences.isEmpty() || part.trim().length() >= 8 && sentences.get(sentences.size() - 1).trim().length() >= 8) {
                  sentences.add(part);
               } else {
                  sentences.set(sentences.size() - 1, sentences.get(sentences.size() - 1) + part);
               }
            }
         }

         List<String> limited = new ArrayList<>();

         for (String sentence : sentences) {
            if (sentence.length() <= maxChars) {
               limited.add(sentence);
            } else {
               for (int start = 0; start < sentence.length(); start += maxChars) {
                  limited.add(sentence.substring(start, Math.min(start + maxChars, sentence.length())));
               }
            }
         }

         return limited;
      }
   }

   /** 计算两向量余弦相似度。 */
   private static double cosine(float[] left, float[] right) {
      int length = Math.min(left.length, right.length);
      double dot = 0.0;
      double leftNorm = 0.0;
      double rightNorm = 0.0;

      for (int i = 0; i < length; i++) {
         dot += left[i] * right[i];
         leftNorm += left[i] * left[i];
         rightNorm += right[i] * right[i];
      }

      return leftNorm != 0.0 && rightNorm != 0.0 ? dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm)) : 0.0;
   }

   /** 计算距离列表的指定百分位阈值。 */
   private static double percentile(List<Double> values, int percentile) {
      List<Double> sorted = new ArrayList<>(values);
      sorted.sort(Double::compare);
      if (sorted.size() == 1) {
         return sorted.get(0);
      } else {
         double rank = percentile / 100.0 * (sorted.size() - 1);
         int low = (int)Math.floor(rank);
         int high = (int)Math.ceil(rank);
         if (low == high) {
            return sorted.get(low);
         } else {
            double weight = rank - low;
            return sorted.get(low) * (1.0 - weight) + sorted.get(high) * weight;
         }
      }
   }

   /** 批量为句子列表生成嵌入向量。 */
   @FunctionalInterface
   public interface SentenceEmbedder {
      /** 嵌入给定句子列表。 */
      List<float[]> embed(List<String> sentences);
   }
}
