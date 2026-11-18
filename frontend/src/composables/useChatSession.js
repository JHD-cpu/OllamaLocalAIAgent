// 管理对话消息、草稿、流式生成与功能开关即时保存。
import { ref } from 'vue'
import { saveSettings, streamChat } from '../api'
import { applyWebPrompt } from '../constants/webPrompt.js'

/** 提供聊天 UI 所需的状态与发送/停止/清空等操作。 */
export function useChatSession({ form, applySettings, syncWebPrompt }) {
  const messages = ref([])
  const draft = ref('')
  const streaming = ref(false)
  const chatError = ref('')
  const featuresBusy = ref(false)
  let abortController = null

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

  /** 发送用户消息并消费 SSE 更新助手气泡。 */
  async function onSend() {
    const text = draft.value.trim()
    if (!text || streaming.value) {
      return
    }
    chatError.value = ''
    messages.value.push({ role: 'user', content: text, thinking: '' })
    messages.value.push({ role: 'assistant', content: '', thinking: '', tools: [], model: '', sources: [] })
    draft.value = ''
    streaming.value = true
    abortController = new AbortController()
    const history = messages.value.slice(0, -1).map(({ role, content }) => ({ role, content }))
    const assistant = messages.value[messages.value.length - 1]
    try {
      await streamChat(history, {
        signal: abortController.signal,
        /** 按事件类型累加模型输出、工具进度与引用来源。 */
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
    }
  }

  /** 中止进行中的流式请求。 */
  function onStop() {
    abortController?.abort()
  }

  /** 清空当前会话消息（生成中不可用）。 */
  function onClear() {
    if (streaming.value) {
      return
    }
    messages.value = []
    chatError.value = ''
  }

  return {
    messages,
    draft,
    streaming,
    chatError,
    featuresBusy,
    onToggleFeature,
    onSend,
    onStop,
    onClear
  }
}