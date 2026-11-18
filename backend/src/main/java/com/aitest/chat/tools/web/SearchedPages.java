package com.aitest.chat.tools.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 本轮对话内去重记录搜索/抓取到的网页，供 SSE 推送来源。 */
public final class SearchedPages {
    private final Set<String> seen = new LinkedHashSet<String>();
    private final List<Page> pending = new ArrayList<Page>();

    /** 登记新 http(s) 链接，重复 URL 忽略。 */
    public synchronized void add(String title, String url) {
        String name;
        if (url == null) {
            return;
        }
        String link = url.trim();
        if (!link.startsWith("http://") && !link.startsWith("https://")) {
            return;
        }
        if (!this.seen.add(link)) {
            return;
        }
        String string = name = title == null ? "" : title.trim();
        if (name.isEmpty()) {
            name = link;
        }
        this.pending.add(new Page(name, link));
    }

    /** 取出并清空待发送的页面列表。 */
    public synchronized List<Page> drain() {
        if (this.pending.isEmpty()) {
            return List.of();
        }
        List<Page> copy = List.copyOf(this.pending);
        this.pending.clear();
        return copy;
    }

    /** 带来源标题与 URL 的网页条目。 */
    public record Page(String title, String url) {

        /** 序列化为前端可解析的 JSON 字符串。 */
        public String json() {
            return "{\"title\":\"" + Page.escape(this.title) + "\",\"url\":\"" + Page.escape(this.url) + "\"}";
        }

        /** 转义 JSON 字符串中的特殊字符。 */
        private static String escape(String value) {
            StringBuilder builder = new StringBuilder(value.length() + 8);
            block7: for (int i = 0; i < value.length(); ++i) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '\\': {
                        builder.append("\\\\");
                        continue block7;
                    }
                    case '\"': {
                        builder.append("\\\"");
                        continue block7;
                    }
                    case '\n': {
                        builder.append("\\n");
                        continue block7;
                    }
                    case '\r': {
                        builder.append("\\r");
                        continue block7;
                    }
                    case '\t': {
                        builder.append("\\t");
                        continue block7;
                    }
                    default: {
                        if (ch < ' ') {
                            builder.append(String.format("\\u%04x", ch));
                            continue block7;
                        }
                        builder.append(ch);
                    }
                }
            }
            return builder.toString();
        }
    }
}
