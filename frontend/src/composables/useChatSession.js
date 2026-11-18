// 管理多会话、草稿、流式生成、持久化与功能开关。
import { ref } from 'vue'
import {
  createConversation,
  deleteConversation,
  getConversationMessages,
  listConversations,
  renameConversation,
  saveConversationMessages,
  saveSettings,
  streamChat,
  uploadDocument
} from '../api'
import { applyWebPrompt } from '../constants/webPrompt.js'

/** 是否为图片 data URL。 */
function isImageDataUrl(dataUrl) {
  return typeof dataUrl === 'string' && /^data:image\//i.test(dataUrl)
}

/** 规范化持久化的 images 字段（字符串或 {name,dataUrl}）。 */
function normalizeMediaList(rawImages) {
  const images = []
  const attachments = []
  for (const item of Array.isArray(rawImages) ? rawImages : []) {
    if (typeof item === 'string' && item) {
      images.push(item)
      attachments.push({
        name: isImageDataUrl(item) ? '图片' : '附件',
        kind: isImageDataUrl(item) ? 'image' : 'file'
      })
    }
    else if (item && typeof item === 'object' && item.dataUrl) {
      const dataUrl = String(item.dataUrl)
      images.push(dataUrl)
      attachments.push({
        name: item.name || (isImageDataUrl(dataUrl) ? '图片' : '附件'),
        kind: isImageDataUrl(dataUrl) ? 'image' : 'file'
      })
    }
  }
  return { images, attachments }
}

/** 规范化一条 UI 消息。 */
function normalizeMessage(raw) {
  const media = normalizeMediaList(raw.images)
  return {
    role: raw.role,
    content: raw.content || '',
    thinking: raw.thinking || '',
    tools: Array.isArray(raw.tools) ? raw.tools : [],
    model: raw.model || '',
    sources: Array.isArray(raw.sources) ? raw.sources : [],
    images: media.images,
    attachments: media.attachments
  }
}

/** 将草稿附件转为发送用结构；文档保留 File 以便入库，避免把大文件塞进对话 JSON。 */
function mediaFromDraft(attachments) {
  return (attachments || [])
    .filter((a) => a && (a.dataUrl || a.file))
    .map((a) => {
      const dataUrl = a.dataUrl || ''
      const kind = a.kind || (isImageDataUrl(dataUrl) ? 'image' : 'file')
      return {
        name: a.name || '附件',
        dataUrl,
        file: a.file || null,
        kind
      }
    })
}

/** data URL → File，供历史重发时入库。 */
function dataUrlToFile(dataUrl, name) {
  const match = String(dataUrl || '').match(/^data:([^;,]+)?(;base64)?,(.*)$/i)
  if (!match) {
    throw new Error('无法读取附件')
  }
  const mime = match[1] || 'application/octet-stream'
  const binary = atob(match[3] || '')
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  return new File([bytes], name || 'attachment.bin', { type: mime })
}

/**
 * 图片走多模态；文档先上传知识库，不把整文件 base64 打进 /api/chat/stream（否则易 400）。
 */
async function prepareOutboundMedia(media) {
  const imageUrls = []
  const notes = []
  const attachmentMeta = []
  for (const item of media || []) {
    if (item.kind === 'image' && item.dataUrl) {
      imageUrls.push(item.dataUrl)
      attachmentMeta.push({ name: item.name, kind: 'image' })
      continue
    }
    const file = item.file || (item.dataUrl ? dataUrlToFile(item.dataUrl, item.name) : null)
    if (!file) {
      continue
    }
    await uploadDocument(file)
    notes.push(`已上传知识库并排队解析：${item.name}。请结合知识库回答（请开启「知识库」检索）。`)
    attachmentMeta.push({ name: item.name, kind: 'file' })
  }
  return { imageUrls, notes, attachmentMeta }
}

/** 重生成时去掉正文里的附件名提示，避免与草稿附件重复拼接。 */
function stripFileHints(content) {
  let text = content || ''
  if (text === '[图片]' || text === '[附件]') {
    return ''
  }
  return text.replace(/(?:\n\n)?(?:附件：[^\n]+\n?)+$/u, '').trim()
}

/** 拼用户可见正文：保留附件名提示，二进制本身走多模态 images。 */
function composeUserContent(text, media) {
  const names = media.filter((m) => m.kind === 'file').map((m) => `附件：${m.name}`)
  const parts = []
  if (text) {
    parts.push(text)
  }
  if (names.length) {
    parts.push(names.join('\n'))
  }
  if (parts.length) {
    return parts.join('\n\n')
  }
  if (media.some((m) => m.kind === 'image')) {
    return '[图片]'
  }
  return media.length ? '[附件]' : ''
}

/** 提供聊天 UI 所需的状态与发送/停止/清空/会话等操作。 */
export function useChatSession({ form, applySettings, syncWebPrompt }) {
  const sessions = ref([])
  const sessionId = ref('')
  const messages = ref([])
  const draft = ref('')
  /** @type {import('vue').Ref<Array<{ id: string, name: string, dataUrl: string }>>} */
  const draftAttachments = ref([])
  const streaming = ref(false)
  const chatError = ref('')
  const featuresBusy = ref(false)
  const sessionsLoading = ref(false)
  let abortController = null

  /** 持久化当前会话消息（忽略失败以免打断对话）。 */
  async function persistMessages() {
    if (!sessionId.value) {
      return
    }
    try {
      const payload = messages.value.map((m) => {
        const imgs = m.images || []
        const meta = m.attachments || []
        return {
          role: m.role,
          content: m.content || '',
          thinking: m.thinking || '',
          tools: m.tools || [],
          sources: m.sources || [],
          model: m.model || '',
          images: imgs.map((dataUrl, i) => ({
            name: meta[i]?.name || (isImageDataUrl(dataUrl) ? `图片${i + 1}` : `附件${i + 1}`),
            dataUrl
          }))
        }
      })
      const body = await saveConversationMessages(sessionId.value, payload)
      if (body?.session) {
        const idx = sessions.value.findIndex((s) => s.id === body.session.id)
        if (idx >= 0) {
          sessions.value[idx] = body.session
        }
        sessions.value = [...sessions.value].sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)))
      }
    }
    catch (error) {
      console.warn('persist messages failed', error)
    }
  }

  /** 加载会话列表；若为空则建一个。 */
  async function loadSessions() {
    sessionsLoading.value = true
    chatError.value = ''
    try {
      let list = await listConversations()
      if (!Array.isArray(list) || list.length === 0) {
        const created = await createConversation('新对话')
        list = [created]
      }
      sessions.value = list
      if (!sessionId.value || !list.some((s) => s.id === sessionId.value)) {
        await selectSession(list[0].id)
      }
    }
    catch (error) {
      chatError.value = error.message
    }
    finally {
      sessionsLoading.value = false
    }
  }

  /** 切换到指定会话并加载消息。 */
  async function selectSession(id) {
    if (!id || streaming.value) {
      return
    }
    chatError.value = ''
    sessionId.value = id
    draft.value = ''
    draftAttachments.value = []
    try {
      const body = await getConversationMessages(id)
      messages.value = (body.messages || []).map(normalizeMessage)
      if (body.session) {
        const idx = sessions.value.findIndex((s) => s.id === body.session.id)
        if (idx >= 0) {
          sessions.value[idx] = body.session
        }
      }
    }
    catch (error) {
      messages.value = []
      chatError.value = error.message
    }
  }

  /** 新建会话并切换过去。 */
  async function onNewSession() {
    if (streaming.value) {
      return
    }
    try {
      const created = await createConversation('新对话')
      sessions.value = [created, ...sessions.value.filter((s) => s.id !== created.id)]
      await selectSession(created.id)
    }
    catch (error) {
      chatError.value = error.message
    }
  }

  /** 重命名当前或指定会话。 */
  async function onRenameSession(id, title) {
    try {
      const updated = await renameConversation(id, title)
      const idx = sessions.value.findIndex((s) => s.id === updated.id)
      if (idx >= 0) {
        sessions.value[idx] = updated
      }
    }
    catch (error) {
      chatError.value = error.message
    }
  }

  /** 删除会话；若删当前则切到其它或新建。 */
  async function onDeleteSession(id) {
    if (streaming.value) {
      return
    }
    try {
      await deleteConversation(id)
      sessions.value = sessions.value.filter((s) => s.id !== id)
      if (sessionId.value === id) {
        if (sessions.value.length === 0) {
          await onNewSession()
        }
        else {
          await selectSession(sessions.value[0].id)
        }
      }
    }
    catch (error) {
      chatError.value = error.message
    }
  }

  /** 切换思考/联网等功能开关并同步保存到后端。 */
  async function onToggleFeature(key, value) {
    if (featuresBusy.value || streaming.value || !(key in form)) {
      return
    }
    const previous = form[key]
    form[key] = value
    if (key === 'webEnabled') {
      syncWebPrompt(value)
    }
    featuresBusy.value = true
    chatError.value = ''
    try {
      const stored = await saveSettings({ ...form })
      applySettings(stored)
    }
    catch (error) {
      form[key] = previous
      if (key === 'webEnabled') {
        form.systemPrompt = applyWebPrompt(form.systemPrompt, previous)
      }
      chatError.value = error.message
    }
    finally {
      featuresBusy.value = false
    }
  }

  /** 发送用户消息并消费 SSE；结束后持久化。 */
  async function onSend() {
    const text = draft.value.trim()
    const media = mediaFromDraft(draftAttachments.value)
    if ((!text && media.length === 0) || streaming.value) {
      return
    }
    if (!sessionId.value) {
      await onNewSession()
    }
    chatError.value = ''
    let prepared
    try {
      prepared = await prepareOutboundMedia(media)
    }
    catch (error) {
      chatError.value = error.message || '附件处理失败'
      return
    }
    const userContent = [composeUserContent(text, media), ...prepared.notes].filter(Boolean).join('\n\n')
    messages.value.push({
      role: 'user',
      content: userContent,
      thinking: '',
      tools: [],
      model: '',
      sources: [],
      images: prepared.imageUrls,
      attachments: prepared.attachmentMeta
    })
    messages.value.push({
      role: 'assistant',
      content: '',
      thinking: '',
      tools: [],
      model: '',
      sources: [],
      images: [],
      attachments: []
    })
    draft.value = ''
    draftAttachments.value = []
    streaming.value = true
    abortController = new AbortController()
    const history = messages.value
      .slice(0, -1)
      .filter((m) => {
        const body = (m.content || '').trim()
        const imgs = Array.isArray(m.images) ? m.images.filter(isImageDataUrl) : []
        if (m.role === 'assistant') {
          return body.length > 0
        }
        return body.length > 0 || imgs.length > 0
      })
      .map((m, index, arr) => {
        const imgs = Array.isArray(m.images) ? m.images.filter(isImageDataUrl) : []
        // 仅最后一条用户消息带图片，避免历史大图撑爆请求体
        const includeImages = index === arr.length - 1 && imgs.length > 0
        return {
          role: m.role,
          content: m.content,
          images: includeImages ? imgs : undefined
        }
      })
    const assistant = messages.value[messages.value.length - 1]
    try {
      await streamChat(history, {
        signal: abortController.signal,
        onEvent(event) {
          if (event.type === 'model') {
            assistant.model = event.text
          }
          else if (event.type === 'thinking') {
            assistant.thinking += event.text
          }
          else if (event.type === 'content') {
            assistant.content += event.text
          }
          else if (event.type === 'tool') {
            assistant.tools.push(event.text)
          }
          else if (event.type === 'source') {
            const page = JSON.parse(event.text)
            if (page.url && !assistant.sources.some((item) => item.url === page.url)) {
              assistant.sources.push({ title: page.title || page.url, url: page.url })
            }
          }
          else if (event.type === 'error') {
            chatError.value = event.text
          }
        }
      })
    }
    catch (error) {
      if (error.name !== 'AbortError') {
        chatError.value = error.message
      }
    }
    finally {
      streaming.value = false
      abortController = null
      if (assistant && !assistant.content && !assistant.thinking && assistant.tools.length === 0 && assistant.sources.length === 0) {
        messages.value.pop()
      }
      messages.value = messages.value.filter((m) => {
        if (m.role !== 'assistant') {
          return true
        }
        return Boolean((m.content || '').trim() || (m.thinking || '').trim() || (m.tools && m.tools.length) || (m.sources && m.sources.length))
      })
      await persistMessages()
    }
  }

  /** 删除末条助手消息并按上一轮用户内容重新生成。 */
  async function onRegenerate() {
    if (streaming.value || messages.value.length < 2) {
      return
    }
    const last = messages.value[messages.value.length - 1]
    const prev = messages.value[messages.value.length - 2]
    if (last.role !== 'assistant' || prev.role !== 'user') {
      return
    }
    messages.value.pop()
    const imgs = Array.isArray(prev.images) ? prev.images : []
    const meta = Array.isArray(prev.attachments) ? prev.attachments : []
    draft.value = stripFileHints(prev.content)
    draftAttachments.value = imgs.map((dataUrl, i) => ({
      id: `regen-${i}-${Date.now()}`,
      name: meta[i]?.name || (isImageDataUrl(dataUrl) ? `图片${i + 1}` : `附件${i + 1}`),
      dataUrl
    }))
    messages.value.pop()
    await onSend()
  }

  /** 中止进行中的流式请求。 */
  function onStop() {
    abortController?.abort()
  }

  /** 清空当前会话消息并持久化。 */
  async function onClear() {
    if (streaming.value) {
      return
    }
    messages.value = []
    chatError.value = ''
    await persistMessages()
  }

  /** 导出当前会话为 Markdown 文本。 */
  function exportMarkdown() {
    const lines = [`# ${sessions.value.find((s) => s.id === sessionId.value)?.title || '对话'}`, '']
    for (const message of messages.value) {
      const who = message.role === 'user' ? '你' : '助手'
      lines.push(`## ${who}`)
      if (message.thinking) {
        lines.push('', '> 思考：', message.thinking)
      }
      if (message.content) {
        lines.push('', message.content)
      }
      lines.push('')
    }
    return lines.join('\n')
  }

  return {
    sessions,
    sessionId,
    messages,
    draft,
    draftAttachments,
    streaming,
    chatError,
    featuresBusy,
    sessionsLoading,
    loadSessions,
    selectSession,
    onNewSession,
    onRenameSession,
    onDeleteSession,
    onToggleFeature,
    onSend,
    onRegenerate,
    onStop,
    onClear,
    exportMarkdown,
    persistMessages
  }
}
