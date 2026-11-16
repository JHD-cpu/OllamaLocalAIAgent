<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import ChatPane from './components/ChatPane.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import { getDefaults, getInfo, getSettings, probeOllama, saveSettings, streamChat } from './api'

// form 是正在编辑的配置，saved 是上次成功保存的副本，两者不同就显示「未保存」。
const form = reactive(emptySettings())
const saved = ref(emptySettings())
const defaults = ref(emptySettings())
const models = ref([])
const info = ref({ ok: false, version: '', message: '正在检查 Ollama…', models: [] })
const messages = ref([])
const draft = ref('')
const streaming = ref(false)
const chatError = ref('')
const settingsError = ref('')
const probeMessage = ref('')
const settingsOpen = ref(false)
const saving = ref(false)
const probing = ref(false)
// 当前这次流式请求的取消句柄。停止生成时调用 abort。
let abortController = null

const dirty = computed(() => JSON.stringify(form) !== JSON.stringify(saved.value))
const statusText = computed(() => {
  if (info.value.ok) {
    return `已连接 ${info.value.version}`
  }
  return info.value.message || '未连接'
})

// 接口还没返回时用的占位配置，字段要和后端 AppSettings 对齐。
function emptySettings() {
  return {
    baseUrl: 'http://127.0.0.1:11434',
    model: 'qwen3.5:9b',
    systemPrompt: '',
    temperature: 0.7,
    topP: 0.9,
    topK: 40,
    numCtx: 8192,
    numPredict: -1,
    repeatPenalty: 1.1,
    think: false,
    webEnabled: false,
    webProvider: 'bing',
    webSearchUrl: 'http://127.0.0.1:8888',
    webMaxResults: 5,
    webFetch: true,
    webMaxRounds: 3
  }
}

// 用服务端返回的配置同时填表单和「已保存」副本，避免刚加载就被当成未保存。
function applySettings(value) {
  Object.assign(form, value)
  saved.value = { ...value }
}

onMounted(async () => {
  try {
    const [current, base] = await Promise.all([getSettings(), getDefaults()])
    applySettings(current)
    defaults.value = base
    await refreshInfo()
  }
  catch (error) {
    info.value = { ok: false, version: '', message: error.message, models: [] }
  }
})

// 用已保存的地址刷新连接状态和模型列表。
async function refreshInfo() {
  const result = await getInfo()
  info.value = result
  models.value = result.models || []
}

// 保存当前表单。成功后用返回值覆盖本地状态，再重新探测 Ollama。
async function onSave() {
  saving.value = true
  settingsError.value = ''
  probeMessage.value = ''
  try {
    const stored = await saveSettings({ ...form })
    applySettings(stored)
    await refreshInfo()
  }
  catch (error) {
    settingsError.value = error.message
  }
  finally {
    saving.value = false
  }
}

// 只测试表单里的地址，不写入配置。成功时刷新可选模型，失败写到设置错误。
async function onProbe() {
  probing.value = true
  settingsError.value = ''
  probeMessage.value = ''
  try {
    const result = await probeOllama(form.baseUrl)
    models.value = result.models || []
    if (result.ok) {
      probeMessage.value = `连接成功，Ollama ${result.version}，共 ${models.value.length} 个模型`
    }
    else {
      settingsError.value = result.message
    }
  }
  catch (error) {
    settingsError.value = error.message
  }
  finally {
    probing.value = false
  }
}

// 把表单填回默认值，但还不保存，需要再点保存才会生效。
function onReset() {
  Object.assign(form, defaults.value)
  settingsError.value = ''
  probeMessage.value = ''
}

// 发送一条消息：先在列表里放上用户气泡和空的助手气泡，再把 SSE 事件拼进去。
async function onSend() {
  const text = draft.value.trim()
  if (!text || streaming.value) {
    return
  }
  chatError.value = ''
  messages.value.push({ role: 'user', content: text, thinking: '' })
  messages.value.push({ role: 'assistant', content: '', thinking: '', tools: [] })
  draft.value = ''
  streaming.value = true
  abortController = new AbortController()
  // 请求不带刚插入的空助手消息，只带已经完成的历史。
  const history = messages.value.slice(0, -1).map(({ role, content }) => ({ role, content }))
  const assistant = messages.value[messages.value.length - 1]
  try {
    await streamChat(history, {
      signal: abortController.signal,
      onEvent(event) {
        // thinking 和 content 都是增量，tool 是一条独立的进度说明。
        if (event.type === 'thinking') {
          assistant.thinking += event.text
        }
        else if (event.type === 'content') {
          assistant.content += event.text
        }
        else if (event.type === 'tool') {
          assistant.tools.push(event.text)
        }
        else if (event.type === 'error') {
          chatError.value = event.text
        }
      }
    })
  }
  catch (error) {
    // 用户点停止会触发 AbortError，这不是失败，不显示错误。
    if (error.name !== 'AbortError') {
      chatError.value = error.message
    }
  }
  finally {
    streaming.value = false
    abortController = null
    // 一点内容都没有（例如立刻停止）时拿掉空的助手气泡。
    if (assistant && !assistant.content && !assistant.thinking && assistant.tools.length === 0) {
      messages.value.pop()
    }
  }
}

// 中断正在进行的流式请求。后端会停，前端在 onSend 的 finally 里收尾。
function onStop() {
  abortController?.abort()
}

function onClear() {
  if (streaming.value) {
    return
  }
  messages.value = []
  chatError.value = ''
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div>
        <p class="eyebrow">Ollama</p>
        <h1>本地对话</h1>
      </div>
      <div class="topbar-side">
        <p class="model-name">{{ saved.model }}<template v-if="saved.webEnabled"> · 可联网</template></p>
        <p class="status" :class="{ online: info.ok }">{{ statusText }}</p>
        <!-- 窄屏才显示这个按钮；宽屏配置栏一直在右侧。 -->
        <button type="button" class="ghost menu" @click="settingsOpen = !settingsOpen">
          {{ settingsOpen ? '关闭配置' : '配置' }}
        </button>
      </div>
    </header>

    <main class="workspace" :class="{ 'settings-open': settingsOpen }">
      <ChatPane
        :messages="messages"
        :draft="draft"
        :streaming="streaming"
        :error="chatError"
        :model="saved.model"
        @update:draft="draft = $event"
        @send="onSend"
        @stop="onStop"
        @clear="onClear"
      />
      <SettingsPanel
        :form="form"
        :models="models"
        :dirty="dirty"
        :saving="saving"
        :probing="probing"
        :error="settingsError"
        :probe-message="probeMessage"
        @save="onSave"
        @probe="onProbe"
        @reset="onReset"
      />
    </main>
  </div>
</template>
