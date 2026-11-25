<script setup>
// 助手消息 Markdown 渲染（含代码高亮）；用户消息用纯文本转义。
import { computed } from 'vue'
import { Marked } from 'marked'
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import java from 'highlight.js/lib/languages/java'
import python from 'highlight.js/lib/languages/python'
import xml from 'highlight.js/lib/languages/xml'
import json from 'highlight.js/lib/languages/json'
import bash from 'highlight.js/lib/languages/bash'
import sql from 'highlight.js/lib/languages/sql'
import 'highlight.js/styles/github-dark.css'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('ts', typescript)
hljs.registerLanguage('java', java)
hljs.registerLanguage('python', python)
hljs.registerLanguage('py', python)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('html', xml)
hljs.registerLanguage('json', json)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', bash)
hljs.registerLanguage('sql', sql)

const marked = new Marked({
  gfm: true,
  breaks: true,
  renderer: {
    code({ text, lang }) {
      const language = (lang || '').trim()
      let highlighted = escapeHtml(text)
      try {
        if (language && hljs.getLanguage(language)) {
          highlighted = hljs.highlight(text, { language }).value
        } else {
          highlighted = hljs.highlightAuto(text).value
        }
      } catch {
        /* keep escaped */
      }
      const cls = language ? ` class="hljs language-${escapeHtml(language)}"` : ' class="hljs"'
      return `<pre><code${cls}>${highlighted}</code></pre>\n`
    }
  }
})

const props = defineProps({
  text: { type: String, default: '' },
  plain: { type: Boolean, default: false }
})

const html = computed(() => {
  const raw = props.text || ''
  if (props.plain || !raw.trim()) {
    return escapeHtml(raw).replace(/\n/g, '<br>')
  }
  return marked.parse(raw)
})

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}
</script>

<template>
  <div class="md-body" v-html="html" />
</template>

<style scoped>
.md-body {
  line-height: 1.65;
  word-break: break-word;
  overflow-wrap: anywhere;
}
.md-body :deep(pre) {
  margin: 0.6em 0;
  padding: 0.75em 1em;
  border-radius: 8px;
  overflow-x: auto;
  background: #0d1117;
  font-size: 0.86em;
}
.md-body :deep(code) {
  font-family: Consolas, 'Cascadia Code', monospace;
  font-size: 0.92em;
}
.md-body :deep(:not(pre) > code) {
  padding: 0.1em 0.35em;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
}
.md-body :deep(p) {
  margin: 0.4em 0;
}
.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 0.4em 0;
  padding-left: 1.4em;
}
.md-body :deep(a) {
  color: #7db7ff;
}
.md-body :deep(table) {
  border-collapse: collapse;
  margin: 0.6em 0;
  width: 100%;
  font-size: 0.92em;
}
.md-body :deep(th),
.md-body :deep(td) {
  border: 1px solid rgba(255, 255, 255, 0.12);
  padding: 0.35em 0.55em;
}
</style>
