package com.aitest.chat.tools.web;

import com.aitest.chat.llm.ollama.OllamaClientFactory;
import com.aitest.chat.settings.AppSettings;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/** 用本地模型把用户问题改写成多条搜索关键词。 */
@Component
public class SearchQueryRewriter {
    private static final Logger log = LoggerFactory.getLogger(SearchQueryRewriter.class);
    private static final int MAX_QUERIES = 3;
    private final OllamaClientFactory clientFactory;

    /** 注入 Ollama 客户端工厂。 */
    public SearchQueryRewriter(OllamaClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /** 调用小温度补全解析多行关键词，失败则退回原问题。 */
    public List<String> rewrite(AppSettings settings, String query) {
        try {
            String today = LocalDate.now(ZoneId.systemDefault()).toString();
            String instruction = "\u628a\u7528\u6237\u95ee\u9898\u6539\u5199\u6210 2 \u5230 3 \u6761\u9002\u5408\u641c\u7d22\u5f15\u64ce\u7684\u5173\u952e\u8bcd\u3002\u6bcf\u884c\u4e00\u6761\uff0c\u4e0d\u8981\u7f16\u53f7\uff0c\u4e0d\u8981\u89e3\u91ca\u3002\u6d89\u53ca\u6700\u8fd1\u3001\u6700\u65b0\u6216\u4eca\u5e74\u65f6\u5199\u4e0a\u5f53\u524d\u5e74\u4efd\u3002\u5f53\u524d\u65e5\u671f\uff1a" + today;
            OllamaChatOptions options = ((OllamaChatOptions.Builder)((OllamaChatOptions.Builder)((OllamaChatOptions.Builder)((OllamaChatOptions.Builder)OllamaChatOptions.builder().model(settings.model())).temperature(Double.valueOf(0.2))).numPredict(Integer.valueOf(160))).disableThinking()).build();
            Prompt prompt = new Prompt(List.of(new SystemMessage(instruction), new UserMessage(query)), (ChatOptions)options);
            ChatResponse response = this.clientFactory.get(settings.baseUrl()).call(prompt);
            String text = response.getResult() == null || response.getResult().getOutput() == null ? "" : response.getResult().getOutput().getText();
            List<String> queries = SearchQueryRewriter.parse(query, text);
            log.info("\u67e5\u8be2\u6539\u5199 \"{}\" -> {}", (Object)query, queries);
            return queries;
        }
        catch (RuntimeException ex) {
            log.warn("\u67e5\u8be2\u6539\u5199\u5931\u8d25\uff0c\u6539\u7528\u539f\u95ee\u9898 query=\"{}\"", (Object)query, (Object)ex);
            return List.of(query);
        }
    }

    /** 从模型输出中提取至多 3 条干净查询行。 */
    static List<String> parse(String original, String modelText) {
        String text = modelText == null ? "" : modelText.replaceAll("(?s)<think>.*?</think>", "").replace("```", "");
        LinkedHashSet<String> queries = new LinkedHashSet<String>();
        for (String line : text.split("\\R")) {
            String cleaned = line.trim().replaceFirst("^(?:[-*\u2022]|\\d+[.)\u3001])\\s*", "");
            if (cleaned.isEmpty() || cleaned.length() > 200) continue;
            queries.add(cleaned);
            if (queries.size() == 3) break;
        }
        if (queries.isEmpty()) {
            return List.of(original);
        }
        return new ArrayList<String>(queries);
    }
}
