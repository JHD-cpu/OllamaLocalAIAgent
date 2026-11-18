// 将文档分片按「知识点」归并：优先解析分片头里的 主题/子主题/概念。

const HEADER_TOPIC = /^【主题】\s*(.+)\s*$/
const HEADER_SUB = /^【子主题】\s*(.+)\s*$/
const HEADER_CONCEPT = /^【概念】\s*(.+)\s*$/

const SECTION_HINT =
  /(教育背景|教育经历|学历|工作经历|工作经验|项目经历|项目经验|实习经历|实习经验|专业技能|个人技能|技能清单|自我评价|个人简介|基本信息|联系方式|获奖情况|荣誉奖励|校园经历|科研成果|证书|语言能力)/

const MARKDOWN_HEADING = /^#{1,6}\s+\S/

const DATE_HINT = /(20\d{2}|19\d{2})\s*[./年\-]/

/**
 * @param {Array<{ id: string, index: number, text: string, charCount?: number }>} chunks
 * @param {{ documentId: string, filename: string }} doc
 */
export function mergeChunksToKnowledgePoints(chunks, doc) {
  const ordered = [...(chunks || [])].sort((a, b) => (a.index ?? 0) - (b.index ?? 0))
  if (!ordered.length) {
    return []
  }

  /** @type {Array<{ key: string, label: string, path: string, chunks: typeof ordered }>} */
  const groups = []
  let current = null

  for (const chunk of ordered) {
    const text = String(chunk.text || '').replace(/\r\n/g, '\n').trim()
    const structured = parseStructuredPath(text)
    const start = structured.label || knowledgePointStart(text)
    if (!current || start) {
      const label = start || fallbackLabel(text, chunk.index)
      const key = normalizeKey(structured.path || label)
      if (current && current.key === key) {
        current.chunks.push(chunk)
        continue
      }
      current = {
        key,
        label,
        path: structured.path || label,
        chunks: [chunk]
      }
      groups.push(current)
      continue
    }
    current.chunks.push(chunk)
  }

  return groups.map((group, i) => {
    const mergedText = group.chunks
      .map((c) => String(c.text || '').trim())
      .filter(Boolean)
      .join('\n\n')
    const first = group.chunks[0]
    return {
      id: `${doc.documentId}:kp:${group.key}:${i}`,
      label: group.label,
      text: mergedText,
      charCount: mergedText.length,
      index: first?.index ?? i,
      documentId: doc.documentId,
      filename: doc.filename,
      chunkIds: group.chunks.map((c) => c.id),
      chunkCount: group.chunks.length,
      path: group.path
    }
  })
}

function parseStructuredPath(text) {
  const lines = String(text || '').split('\n').map((l) => l.trim())
  let topic = ''
  let subtopic = ''
  let concept = ''
  for (const line of lines.slice(0, 8)) {
    const t = line.match(HEADER_TOPIC)
    if (t) {
      topic = t[1].trim()
      continue
    }
    const s = line.match(HEADER_SUB)
    if (s) {
      subtopic = s[1].trim()
      continue
    }
    const c = line.match(HEADER_CONCEPT)
    if (c) {
      concept = c[1].trim()
      continue
    }
    if (topic || subtopic || concept) {
      break
    }
  }
  const parts = [topic, subtopic, concept].filter(Boolean)
  if (!parts.length) {
    return { label: '', path: '' }
  }
  return {
    label: concept || subtopic || topic,
    path: parts.join(' / ')
  }
}

function knowledgePointStart(text) {
  if (!text) {
    return ''
  }
  const lines = text.split('\n').map((l) => l.trim()).filter(Boolean)
  if (!lines.length) {
    return ''
  }
  const first = lines[0]
  if (isHeadingLine(first)) {
    return cleanLabel(first)
  }
  if (lines.length >= 2 && SECTION_HINT.test(first) && isTitleLike(lines[1])) {
    return cleanLabel(lines[1])
  }
  return ''
}

function isHeadingLine(line) {
  if (!line || line.length > 80) {
    return false
  }
  if (MARKDOWN_HEADING.test(line)) {
    return true
  }
  if (SECTION_HINT.test(line) && line.length <= 40) {
    return true
  }
  if (DATE_HINT.test(line) && line.length <= 72) {
    return true
  }
  return isTitleLike(line)
}

function isTitleLike(line) {
  if (!line || line.length < 2 || line.length > 48) {
    return false
  }
  if (/[。！？；;]$/.test(line)) {
    return false
  }
  if (line.length > 20 && /[，,]/.test(line)) {
    return false
  }
  if (line.length > 36 && /的|了|是|在|与|和|为/.test(line)) {
    return false
  }
  return true
}

function cleanLabel(line) {
  return String(line || '')
    .replace(/^#{1,6}\s+/, '')
    .replace(/\s+/g, ' ')
    .trim()
}

function normalizeKey(label) {
  return cleanLabel(label).toLowerCase().replace(/\s+/g, '')
}

function fallbackLabel(text, index) {
  const first = String(text || '')
    .split(/[。！？\n]/)[0]
    ?.replace(/\s+/g, ' ')
    .trim()
  if (first) {
    return first.length > 24 ? `${first.slice(0, 24)}…` : first
  }
  return `知识点 ${(index ?? 0) + 1}`
}
