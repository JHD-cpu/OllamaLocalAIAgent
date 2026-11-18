package com.aitest.chat.knowledge.indexing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.aitest.chat.knowledge.indexing.DocumentChunkPlan.SplitPoint;
import com.aitest.chat.knowledge.indexing.SemanticChunker.SentenceEmbedder;

/** 按主题树叶节点锚点切分，并给每块加上「主题/子主题/概念」标题头。 */
final class PlannedChunker {

	private PlannedChunker() {
	}

	static List<String> split(
		String text,
		DocumentChunkPlan plan,
		SentenceEmbedder embedder,
		double breakpointPercentile
	) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		String normalized = text.replace("\r\n", "\n").trim();
		List<SplitPoint> leaves = plan.flattenLeaves();
		if (leaves.isEmpty()) {
			return List.of();
		}
		List<Located> located = locate(normalized, leaves);
		if (located.isEmpty()) {
			return List.of();
		}
		List<String> chunks = new ArrayList<>();
		int first = located.get(0).index;
		if (first > 0) {
			String preface = normalized.substring(0, first).trim();
			if (!preface.isEmpty()) {
				chunks.addAll(limit(prefix("前言", "", "", preface), plan.maxChunkChars(), embedder, breakpointPercentile));
			}
		}
		for (int i = 0; i < located.size(); i++) {
			Located cur = located.get(i);
			int end = i + 1 < located.size() ? located.get(i + 1).index : normalized.length();
			String body = normalized.substring(cur.index, end).trim();
			if (body.isEmpty()) {
				continue;
			}
			String headed = cur.point.header() + body;
			chunks.addAll(limit(headed, plan.maxChunkChars(), embedder, breakpointPercentile));
		}
		List<String> trimmed = chunks.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
		// 主题叶仍可能过碎：按上限再打包合并
		return SemanticChunker.pack(trimmed, plan.maxChunkChars(), Math.min(1200, plan.maxChunkChars() / 2));
	}

	private static List<String> limit(
		String text,
		int maxChars,
		SentenceEmbedder embedder,
		double breakpointPercentile
	) {
		if (text.length() <= maxChars) {
			return List.of(text);
		}
		return SemanticChunker.split(text, embedder, (int) Math.round(breakpointPercentile), maxChars);
	}

	private static String prefix(String topic, String subtopic, String concept, String body) {
		SplitPoint point = new SplitPoint(topic, subtopic, concept, topic, topic);
		return point.header() + body;
	}

	private static List<Located> locate(String text, List<SplitPoint> leaves) {
		record Hit(int index, SplitPoint point, int order) {
		}
		List<Hit> hits = new ArrayList<>();
		int searchFrom = 0;
		for (int i = 0; i < leaves.size(); i++) {
			SplitPoint point = leaves.get(i);
			String anchor = point.anchor() == null ? "" : point.anchor().trim();
			if (anchor.isBlank()) {
				continue;
			}
			int at = text.indexOf(anchor, searchFrom);
			if (at < 0) {
				at = text.indexOf(anchor);
			}
			if (at < 0) {
				continue;
			}
			hits.add(new Hit(at, point, i));
			searchFrom = at + Math.max(1, anchor.length());
		}
		if (hits.isEmpty()) {
			return List.of();
		}
		hits.sort(Comparator.comparingInt(Hit::index).thenComparingInt(Hit::order));
		List<Located> unique = new ArrayList<>();
		int lastEnd = -1;
		for (Hit hit : hits) {
			if (hit.index() < lastEnd) {
				continue;
			}
			unique.add(new Located(hit.index(), hit.point()));
			lastEnd = hit.index() + Math.max(1, hit.point().anchor().length());
		}
		return unique;
	}

	private record Located(int index, SplitPoint point) {
	}

}
