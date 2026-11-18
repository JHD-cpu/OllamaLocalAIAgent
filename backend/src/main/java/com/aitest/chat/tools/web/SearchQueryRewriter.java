package com.aitest.chat.tools.web;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.aitest.chat.llm.ChatModelFactory;
import com.aitest.chat.llm.ChatOptionsFactory;
import com.aitest.chat.settings.AppSettings;

/** 用当前配置的模型把用户问题改写成多条搜索关键词。 */
@Component
public class SearchQueryRewriter {

	private static final Logger log = LoggerFactory.getLogger(SearchQueryRewriter.class);

	private final ChatModelFactory clientFactory;

	private final ChatOptionsFactory optionsFactory;

	public SearchQueryRewriter(ChatModelFactory clientFactory, ChatOptionsFactory optionsFactory) {
		this.clientFactory = clientFactory;
		this.optionsFactory = optionsFactory;
	}

	public List<String> rewrite(AppSettings settings, String query) {
		try {
			String today = LocalDate.now(ZoneId.systemDefault()).toString();
			String instruction = "把用户问题改写成 2 到 3 条适合搜索引擎的关键词。每行一条，不要编号，不要解释。涉及最近、最新或今年时写上当前年份。当前日期："
					+ today;
			AppSettings lowTemp = new AppSettings(settings.llmProvider(), settings.apiKey(), settings.baseUrl(),
					settings.model(), settings.systemPrompt(), 0.2, settings.topP(), settings.topK(), settings.numCtx(),
					160, settings.repeatPenalty(), false, settings.webEnabled(), settings.webProvider(),
					settings.webSearchUrl(), settings.webMaxResults(), settings.webFetch(), settings.webMaxRounds(),
					settings.ragEnabled(), settings.embeddingModel(), settings.ragTopK(), settings.codeEnabled(),
					settings.codeWorkspace(), settings.codeTimeoutSeconds(), settings.codeMaxRounds());
			Prompt prompt = new Prompt(List.of(new SystemMessage(instruction), new UserMessage(query)),
					this.optionsFactory.create(lowTemp, false, null));
			ChatResponse response = this.clientFactory.get(settings).call(prompt);
			String text = response.getResult() == null || response.getResult().getOutput() == null ? ""
					: response.getResult().getOutput().getText();
			List<String> queries = parse(query, text);
			log.info("查询改写 \"{}\" -> {}", query, queries);
			return queries;
		}
		catch (RuntimeException ex) {
			log.warn("查询改写失败，改用原问题 query=\"{}\"", query, ex);
			return List.of(query);
		}
	}

	static List<String> parse(String original, String modelText) {
		String text = modelText == null ? "" : modelText.replaceAll("(?s)<think>.*?</think>", "").replace("```", "");
		LinkedHashSet<String> queries = new LinkedHashSet<>();
		for (String line : text.split("\\R")) {
			String cleaned = line.trim().replaceFirst("^(?:[-*•]|\\d+[.)、])\\s*", "");
			if (cleaned.isEmpty() || cleaned.length() > 200) {
				continue;
			}
			queries.add(cleaned);
			if (queries.size() == 3) {
				break;
			}
		}
		if (queries.isEmpty()) {
			return List.of(original);
		}
		return new ArrayList<>(queries);
	}

}
