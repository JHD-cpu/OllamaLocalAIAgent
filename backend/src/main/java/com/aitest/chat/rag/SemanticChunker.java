package com.aitest.chat.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * 按语义切块：先拆成句子，用嵌入向量算相邻句子的距离，距离突然变大就断开。
 * 距离用 1 减余弦相似度。阈值取全部相邻距离的百分位，所以一份内部很整齐的文档不会被切碎。
 * chunkMaxChars 只是上限，避免一个主题长到嵌不进去。
 */
public final class SemanticChunker {

	private SemanticChunker() {
	}

	@FunctionalInterface
	public interface SentenceEmbedder {

		List<float[]> embed(List<String> sentences);

	}

	public static List<String> split(String text, SentenceEmbedder embedder, int breakpointPercentile, int maxChars) {
		if (breakpointPercentile < 1 || breakpointPercentile > 99 || maxChars < 1) {
			throw new IllegalArgumentException("语义切分参数不正确");
		}
		List<String> sentences = sentences(text, maxChars);
		if (sentences.isEmpty()) {
			return List.of();
		}
		if (sentences.size() == 1) {
			return List.of(sentences.get(0).trim());
		}
		List<float[]> vectors = embedder.embed(sentences);
		if (vectors.size() != sentences.size()) {
			throw new IllegalStateException("嵌入条数和句子数不一致");
		}
		double[] distances = new double[sentences.size() - 1];
		List<Double> distanceList = new ArrayList<>();
		for (int i = 0; i < distances.length; i++) {
			distances[i] = 1 - cosine(vectors.get(i), vectors.get(i + 1));
			distanceList.add(distances[i]);
		}
		double threshold = distances.length == 1 ? 0.5 : percentile(distanceList, breakpointPercentile);
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder(sentences.get(0));
		for (int i = 1; i < sentences.size(); i++) {
			String next = sentences.get(i);
			boolean topicChanged = distances[i - 1] > threshold;
			boolean tooLong = current.length() + next.length() > maxChars;
			if (topicChanged || tooLong) {
				String chunk = current.toString().trim();
				if (!chunk.isEmpty()) {
					chunks.add(chunk);
				}
				current = new StringBuilder(next);
			}
			else {
				current.append(next);
			}
		}
		String tail = current.toString().trim();
		if (!tail.isEmpty()) {
			chunks.add(tail);
		}
		return chunks;
	}

	/** 按句号、问号、叹号和换行切开，过短的碎片并回上一句。超长句子再按字数硬切开。 */
	static List<String> sentences(String text, int maxChars) {
		String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
		if (normalized.isEmpty()) {
			return List.of();
		}
		String[] parts = normalized.split("(?<=[。！？!?；;\\n])");
		List<String> sentences = new ArrayList<>();
		for (String part : parts) {
			if (part.isBlank()) {
				continue;
			}
			if (!sentences.isEmpty() && (part.trim().length() < 8 || sentences.get(sentences.size() - 1).trim().length() < 8)) {
				sentences.set(sentences.size() - 1, sentences.get(sentences.size() - 1) + part);
			}
			else {
				sentences.add(part);
			}
		}
		List<String> limited = new ArrayList<>();
		for (String sentence : sentences) {
			if (sentence.length() <= maxChars) {
				limited.add(sentence);
				continue;
			}
			for (int start = 0; start < sentence.length(); start += maxChars) {
				limited.add(sentence.substring(start, Math.min(start + maxChars, sentence.length())));
			}
		}
		return limited;
	}

	private static double cosine(float[] left, float[] right) {
		int length = Math.min(left.length, right.length);
		double dot = 0;
		double leftNorm = 0;
		double rightNorm = 0;
		for (int i = 0; i < length; i++) {
			dot += left[i] * right[i];
			leftNorm += left[i] * left[i];
			rightNorm += right[i] * right[i];
		}
		if (leftNorm == 0 || rightNorm == 0) {
			return 0;
		}
		return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
	}

	/** 线性插值百分位，和常见语义切分实现一致。 */
	private static double percentile(List<Double> values, int percentile) {
		List<Double> sorted = new ArrayList<>(values);
		sorted.sort(Double::compare);
		if (sorted.size() == 1) {
			return sorted.get(0);
		}
		double rank = percentile / 100.0 * (sorted.size() - 1);
		int low = (int) Math.floor(rank);
		int high = (int) Math.ceil(rank);
		if (low == high) {
			return sorted.get(low);
		}
		double weight = rank - low;
		return sorted.get(low) * (1 - weight) + sorted.get(high) * weight;
	}

}
