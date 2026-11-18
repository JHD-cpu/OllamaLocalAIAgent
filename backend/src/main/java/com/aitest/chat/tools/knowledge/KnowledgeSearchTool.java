package com.aitest.chat.tools.knowledge;

import com.aitest.chat.api.KnowledgeHit;
import com.aitest.chat.knowledge.application.KnowledgeService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 调用向量知识库检索，把命中片段交还给模型。 */
public class KnowledgeSearchTool {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);
    private final KnowledgeService knowledgeService;
    private final int topK;

    /** 绑定检索服务与命中条数上限。 */
    public KnowledgeSearchTool(KnowledgeService knowledgeService, int topK) {
        this.knowledgeService = knowledgeService;
        this.topK = topK;
    }

    /** 按问题检索 topK 片段并格式化为带来源说明的文本。 */
    @Tool(name="knowledge_search", description="\u68c0\u7d22\u7528\u6237\u5df2\u4e0a\u4f20\u7684\u77e5\u8bc6\u5e93\u3002\u95ee\u9898\u6d89\u53ca\u4e0a\u4f20\u7684\u6587\u6863\u3001\u7b80\u5386\u3001\u5236\u5ea6\u6216\u7b14\u8bb0\u65f6\u4f7f\u7528\u3002\u65b0\u95fb\u3001\u5929\u6c14\u3001\u4ef7\u683c\u7b49\u5b9e\u65f6\u4fe1\u606f\u4e0d\u8981\u4f7f\u7528\u3002")
    public String knowledgeSearch(@ToolParam(description="\u8981\u5728\u77e5\u8bc6\u5e93\u4e2d\u67e5\u627e\u7684\u95ee\u9898", required=true) String query) {
        String text;
        String string = text = query == null ? "" : query.trim();
        if (text.isEmpty() || text.length() > 2000) {
            return "\u68c0\u7d22\u95ee\u9898\u4e0d\u80fd\u4e3a\u7a7a\uff0c\u4e14\u4e0d\u80fd\u8d85\u8fc7 2000 \u4e2a\u5b57\u7b26\u3002";
        }
        try {
            log.info("\u68c0\u7d22\u77e5\u8bc6\u5e93 topK={} question=\"{}\"", (Object)this.topK, (Object)KnowledgeSearchTool.preview(text));
            List<KnowledgeHit> hits = this.knowledgeService.retrieve(text, this.topK);
            if (hits.isEmpty()) {
                log.info("\u77e5\u8bc6\u5e93\u6ca1\u6709\u547d\u4e2d topK={} question=\"{}\"", (Object)this.topK, (Object)KnowledgeSearchTool.preview(text));
                return "\u77e5\u8bc6\u5e93\u6ca1\u6709\u76f8\u5173\u8d44\u6599\u3002";
            }
            log.info("\u77e5\u8bc6\u5e93\u547d\u4e2d {} \u6761 files={}", (Object)hits.size(), hits.stream().map(KnowledgeHit::filename).distinct().toList());
            return KnowledgeSearchTool.format(hits);
        }
        catch (RuntimeException ex) {
            log.warn("\u77e5\u8bc6\u5e93\u68c0\u7d22\u5931\u8d25 question=\"{}\"", (Object)KnowledgeSearchTool.preview(text), (Object)ex);
            String message = ex.getMessage() == null ? "\u77e5\u8bc6\u5e93\u68c0\u7d22\u5931\u8d25\u3002" : ex.getMessage();
            return message.length() > 200 ? message.substring(0, 200) : message;
        }
    }

    /** 把命中列表拼成带编号与文件名前缀的提示文本。 */
    private static String format(List<KnowledgeHit> hits) {
        StringBuilder note = new StringBuilder("\u4ee5\u4e0b\u662f\u77e5\u8bc6\u5e93\u4e2d\u68c0\u7d22\u5230\u7684\u8d44\u6599\u3002\u8bf7\u4f18\u5148\u4f9d\u636e\u8fd9\u4e9b\u8d44\u6599\u56de\u7b54\uff0c\u5e76\u5728\u56de\u7b54\u4e2d\u6ce8\u660e\u6765\u6e90\u6587\u4ef6\u540d\u3002\u8d44\u6599\u6ca1\u6709\u8986\u76d6\u7684\u95ee\u9898\u8981\u8bf4\u660e\u4e0d\u77e5\u9053\uff0c\u4e0d\u8981\u7f16\u9020\u6765\u6e90\u3002\n\n");
        for (int i = 0; i < hits.size(); ++i) {
            KnowledgeHit hit = hits.get(i);
            note.append('[').append(i + 1).append("] \u6765\u6e90\uff1a").append(hit.filename()).append('\n');
            note.append(hit.text()).append("\n\n");
        }
        return note.toString().trim();
    }

    /** 日志用单行摘要，过长截断。 */
    private static String preview(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "\u2026" : oneLine;
    }
}
