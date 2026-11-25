package com.aitest.chat.knowledge.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 结构优先 + 语义断点的分块：
 * 先按章节/项目标题切开，同节尽量整块保留；仅超长节才做句级语义切分，最后合并过碎块。
 */
public final class SemanticChunker {

	/** 低于此长度的块会尽量与相邻块合并，避免分片过碎。 */
	private static final int MIN_CHUNK_CHARS = 900;

	private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*");

	private static final Pattern SECTION_TITLE = Pattern.compile(
			".*(教育背景|教育经历|学历|工作经历|工作经验|项目经历|项目经验|实习经历|实习经验|专业技能|个人技能|技能清单|自我评价|个人简介|基本信息|联系方式|获奖情况|荣誉奖励|校园经历|科研成果).*");

	private static final Pattern DATE_HINT = Pattern.compile(".*(20\\d{2}|19\\d{2})\\s*[./年\\-].*");

	private SemanticChunker() {
	}

	/** 将全文切成语义连贯且尽量保持结构完整的文本块。 */
	public static List<String> split(String text, SentenceEmbedder embedder, int breakpointPercentile, int maxChars) {
		if (breakpointPercentile < 1 || breakpointPercentile > 99 || maxChars < 1) {
			throw new IllegalArgumentException("语义切分参数不正确");
		}
		List<String> sections = sections(text);
		if (sections.isEmpty()) {
			return List.of();
		}
		List<String> chunks = new ArrayList<>();
		for (String section : sections) {
			String trimmed = section.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			boolean structured = isSectionHeader(firstLine(trimmed));
			if (structured && trimmed.length() <= maxChars) {
				// 简历项目/栏目等：同节尽量整块保留
				chunks.add(trimmed);
			}
			else if (trimmed.length() <= maxChars) {
				// 普通散文：仍用语义断点，避免不同主题粘在一块
				chunks.addAll(semanticSplit(trimmed, embedder, breakpointPercentile, maxChars));
			}
			else {
				chunks.addAll(splitOversizedSection(trimmed, embedder, breakpointPercentile, maxChars));
			}
		}
		return mergeTiny(chunks, maxChars);
	}

	/**
	 * 按结构切成节：Markdown 标题、简历栏目、带日期的项目标题等开启新节。
	 */
	static List<String> sections(String text) {
		String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
		if (normalized.isEmpty()) {
			return List.of();
		}
		String[] lines = normalized.split("\n", -1);
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String raw : lines) {
			String line = raw == null ? "" : raw;
			String trimmed = line.trim();
			boolean header = isSectionHeader(trimmed);
			if (header && !current.isEmpty()) {
				result.add(current.toString().trim());
				current.setLength(0);
			}
			if (!current.isEmpty()) {
				current.append('\n');
			}
			current.append(line);
		}
		String tail = current.toString().trim();
		if (!tail.isEmpty()) {
			result.add(tail);
		}
		return result;
	}

	/** 知识点头：合并时必须作为硬边界，避免【主题】落入块中部。 */
	static boolean isKnowledgeHeader(String line) {
		if (line == null || line.isBlank()) {
			return false;
		}
		String t = line.trim();
		return t.startsWith("【主题】") || t.startsWith("【子主题】") || t.startsWith("【概念】");
	}

	/** 判断是否为应单独起节的标题行。 */
	static boolean isSectionHeader(String line) {
		if (line == null || line.isBlank()) {
			return false;
		}
		String t = line.trim();
		if (isKnowledgeHeader(t)) {
			return true;
		}
		if (MARKDOWN_HEADING.matcher(t).matches()) {
			return true;
		}
		if (t.length() > 48) {
			return false;
		}
		if (t.endsWith("。") || t.endsWith("！") || t.endsWith("？") || t.endsWith(".") || t.endsWith("!")
				|| t.endsWith("?")) {
			return false;
		}
		String compact = t.replace(" ", "").toLowerCase(Locale.ROOT);
		if (SECTION_TITLE.matcher(compact).matches()) {
			return true;
		}
		// 短行 + 日期：常见于「项目名 | 2021.03-2022.01」
		if (t.length() <= 40 && DATE_HINT.matcher(t).matches()) {
			return true;
		}
		// 纯短标题：无句号、少逗号、像栏目名
		if (t.length() <= 16 && !t.contains("，") && !t.contains(",") && !t.contains("：") && !t.contains(":")) {
			return SECTION_TITLE.matcher(compact).matches() || compact.endsWith("经历") || compact.endsWith("经验")
					|| compact.endsWith("技能") || compact.endsWith("评价") || compact.endsWith("简介");
		}
		return false;
	}

	/** 超长节：先按段落打包，仍超长再句级语义切。 */
	private static List<String> splitOversizedSection(String section, SentenceEmbedder embedder, int breakpointPercentile,
			int maxChars) {
		List<String> paragraphs = paragraphs(section);
		List<String> packed = packParagraphs(paragraphs, maxChars);
		List<String> out = new ArrayList<>();
		for (String block : packed) {
			if (block.length() <= maxChars) {
				out.add(block);
			}
			else {
				out.addAll(semanticSplit(block, embedder, breakpointPercentile, maxChars));
			}
		}
		return out;
	}

	static List<String> paragraphs(String text) {
		String[] parts = text.split("\\n\\s*\\n+");
		List<String> list = new ArrayList<>();
		for (String part : parts) {
			String p = part.trim();
			if (!p.isEmpty()) {
				list.add(p);
			}
		}
		if (list.isEmpty() && text != null && !text.isBlank()) {
			// 没有空行时按单行聚合：连续非空行视为一段
			String[] lines = text.split("\n");
			StringBuilder buf = new StringBuilder();
			for (String line : lines) {
				if (line.isBlank()) {
					if (!buf.isEmpty()) {
						list.add(buf.toString().trim());
						buf.setLength(0);
					}
					continue;
				}
				if (!buf.isEmpty()) {
					buf.append('\n');
				}
				buf.append(line.trim());
			}
			if (!buf.isEmpty()) {
				list.add(buf.toString().trim());
			}
		}
		return list;
	}

	/** 贪心把段落拼到不超过 maxChars。 */
	private static List<String> packParagraphs(List<String> paragraphs, int maxChars) {
		List<String> out = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String paragraph : paragraphs) {
			if (paragraph.length() > maxChars) {
				if (!current.isEmpty()) {
					out.add(current.toString().trim());
					current.setLength(0);
				}
				out.add(paragraph);
				continue;
			}
			int nextLen = current.isEmpty() ? paragraph.length() : current.length() + 1 + paragraph.length();
			if (!current.isEmpty() && nextLen > maxChars) {
				out.add(current.toString().trim());
				current.setLength(0);
			}
			if (!current.isEmpty()) {
				current.append('\n');
			}
			current.append(paragraph);
		}
		if (!current.isEmpty()) {
			out.add(current.toString().trim());
		}
		return out;
	}

	/** 句级语义切分（仅用于无法整节保留的超长块）。 */
	private static List<String> semanticSplit(String text, SentenceEmbedder embedder, int breakpointPercentile,
			int maxChars) {
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
			distances[i] = 1.0 - cosine(vectors.get(i), vectors.get(i + 1));
			distanceList.add(distances[i]);
		}
		double threshold = distances.length == 1 ? 0.55 : percentile(distanceList, breakpointPercentile);
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder(sentences.get(0));
		for (int i = 1; i < sentences.size(); i++) {
			String next = sentences.get(i);
			boolean topicChanged = distances[i - 1] > threshold;
			boolean tooLong = current.length() + next.length() > maxChars;
			if (!topicChanged && !tooLong) {
				current.append(next);
			}
			else {
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

	/** 合并过碎相邻块：短块优先拼满，直到接近 preferMin 或触及 maxChars。 */
	static List<String> mergeTiny(List<String> chunks, int maxChars) {
		return pack(chunks, maxChars, Math.min(MIN_CHUNK_CHARS, Math.max(400, maxChars / 3)));
	}

	/**
	 * 相邻块打包：只要合并后不超过 maxChars，且当前块仍小于 preferMin，就继续合并。
	 * 遇到明确大章节标题时可断开，避免跨章粘连。
	 */
	static List<String> pack(List<String> chunks, int maxChars, int preferMin) {
		if (chunks == null || chunks.size() <= 1) {
			return chunks == null ? List.of() : chunks;
		}
		int minTarget = Math.max(200, preferMin);
		List<String> out = new ArrayList<>();
		StringBuilder current = new StringBuilder(chunks.get(0));
		for (int i = 1; i < chunks.size(); i++) {
			String next = chunks.get(i);
			boolean canMerge = current.length() + 1 + next.length() <= maxChars;
			String nextFirst = firstLine(next);
			// 知识点头与章节/项目标题永远硬断开，避免跨主题粘连
			boolean hardBreak = isKnowledgeHeader(nextFirst) || isSectionHeader(nextFirst);
			// 只吞极碎碎片，不把语义断点或完整短段重新粘回去
			boolean nextIsFragment = isTinyFragment(next);
			boolean needMore = current.length() < minTarget;
			if (canMerge && !hardBreak && needMore && nextIsFragment) {
				current.append('\n').append(next);
			}
			else if (canMerge && !hardBreak && nextIsFragment
					&& current.length() + next.length() < maxChars * 3 / 4) {
				current.append('\n').append(next);
			}
			else {
				out.add(current.toString().trim());
				current = new StringBuilder(next);
			}
		}
		String tail = current.toString().trim();
		if (!tail.isEmpty()) {
			out.add(tail);
		}
		return out;
	}

	private static String firstLine(String text) {
		int n = text.indexOf('\n');
		return n < 0 ? text.trim() : text.substring(0, n).trim();
	}

	/** 极碎残留（无句末标点的短段），可并入上一块；完整短句不吞。 */
	private static boolean isTinyFragment(String text) {
		if (text == null) {
			return false;
		}
		String t = text.trim();
		if (t.isEmpty()) {
			return true;
		}
		if (t.length() >= 120) {
			return false;
		}
		boolean sentenceEnd = t.endsWith("。") || t.endsWith("！") || t.endsWith("？") || t.endsWith(".")
				|| t.endsWith("!") || t.endsWith("?") || t.endsWith("；") || t.endsWith(";");
		// 已是完整短句：保留语义断点，不并回上一块
		if (sentenceEnd) {
			return false;
		}
		return t.length() < 80;
	}

	/** 按标点拆句并限制单句长度。 */
	static List<String> sentences(String text, int maxChars) {
		String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
		if (normalized.isEmpty()) {
			return List.of();
		}
		// 不再按每个换行硬切，避免简历一行一句导致过碎
		String[] parts = normalized.split("(?<=[。！？!?；;])|\\n{2,}");
		List<String> sentences = new ArrayList<>();
		for (String part : parts) {
			if (part.isBlank()) {
				continue;
			}
			String piece = part;
			if (!sentences.isEmpty() && piece.trim().length() < 8) {
				sentences.set(sentences.size() - 1, sentences.get(sentences.size() - 1) + piece);
			}
			else {
				sentences.add(piece);
			}
		}
		List<String> limited = new ArrayList<>();
		for (String sentence : sentences) {
			if (sentence.length() <= maxChars) {
				limited.add(sentence);
			}
			else {
				for (int start = 0; start < sentence.length(); start += maxChars) {
					limited.add(sentence.substring(start, Math.min(start + maxChars, sentence.length())));
				}
			}
		}
		return limited;
	}

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
		return sorted.get(low) * (1.0 - weight) + sorted.get(high) * weight;
	}

	@FunctionalInterface
	public interface SentenceEmbedder {
		List<float[]> embed(List<String> sentences);
	}
}
