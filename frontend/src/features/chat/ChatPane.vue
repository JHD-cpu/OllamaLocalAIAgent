<script setup>
// 对话区：居中消息流 + 底部悬浮输入坞（豆包式 Agent 布局）。
import { computed, nextTick, ref, watch } from 'vue'
import {
  ArrowDown,
  CopyDocument,
  Document,
  Download,
  Plus,
  RefreshRight,
  VideoPause
} from '@element-plus/icons-vue'
import { notifyError, notifySuccess, notifyWarn } from '../../utils/notify.js'
import MarkdownBody from '../../components/MarkdownBody.vue'

const MAX_ATTACHMENTS = 8
/** 与知识库入库白名单对齐（图片另开选择器）。 */
const ACCEPT_DOCS =
  '.pdf,.txt,.md,.markdown,.docx,.xlsx,.xls,application/pdf,text/plain,text/markdown,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const DOC_EXTS = ['.pdf', '.txt', '.md', '.markdown', '.docx', '.xlsx', '.xls']

const props = defineProps({
  messages: { type: Array, required: true },
  draft: { type: String, required: true },
  draftAttachments: { type: Array, default: () => [] },
  streaming: { type: Boolean, required: true },
  error: { type: String, default: '' },
  model: { type: String, default: '' },
  think: { type: Boolean, default: false },
  webEnabled: { type: Boolean, default: false },
  ragEnabled: { type: Boolean, default: false },
  codeEnabled: { type: Boolean, default: false },
  featuresBusy: { type: Boolean, default: false }
})

const emit = defineEmits([
  'update:draft',
  'update:draftAttachments',
  'send',
  'stop',
  'clear',
  'toggle-feature',
  'regenerate',
  'export'
])

const scroller = ref(null)
const openSources = ref({})
const attachMenu = ref(null)
const imageInput = ref(null)
const fileInput = ref(null)
const dragOver = ref(false)
const attaching = ref(false)
let dragDepth = 0

const canSend = computed(
  () => (props.draft.trim().length > 0 || props.draftAttachments.length > 0) && !props.streaming && !attaching.value
)
const canRegenerate = computed(() => {
  if (props.streaming || props.messages.length < 2) {
    return false
  }
  const last = props.messages[props.messages.length - 1]
  const prev = props.messages[props.messages.length - 2]
  return last?.role === 'assistant' && prev?.role === 'user'
})

function toggleSources(index) {
  openSources.value = { ...openSources.value, [index]: !openSources.value[index] }
}

function isHttp(url) {
  return typeof url === 'string' && /^https?:\/\//i.test(url)
}

function isImageDataUrl(dataUrl) {
  return typeof dataUrl === 'string' && /^data:image\//i.test(dataUrl)
}

function messageImages(message) {
  return (message.images || []).filter((url) => isImageDataUrl(url))
}

function messageFiles(message) {
  const imgs = message.images || []
  const meta = message.attachments || []
  return imgs
    .map((dataUrl, i) => ({ dataUrl, name: meta[i]?.name || `附件${i + 1}`, kind: meta[i]?.kind }))
    .filter((item) => item.kind === 'file' || !isImageDataUrl(item.dataUrl))
}

watch(
  () => props.messages,
  async () => {
    await nextTick()
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight
    }
  },
  { deep: true }
)

function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    if (canSend.value) {
      emit('send')
    }
  }
}

function isPending(message, index) {
  return props.streaming && index === props.messages.length - 1 && message.role === 'assistant' && !message.content
}

async function copyMessage(message) {
  const text = message.content || message.thinking || ''
  try {
    await navigator.clipboard.writeText(text)
    notifySuccess('已复制')
  }
  catch {
    notifyError('复制失败')
  }
}

function readAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

function guessMime(file) {
  if (file.type) {
    return file.type
  }
  const name = (file.name || '').toLowerCase()
  if (name.endsWith('.pdf')) return 'application/pdf'
  if (name.endsWith('.docx')) return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
  if (name.endsWith('.doc')) return 'application/msword'
  if (name.endsWith('.xlsx')) return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  if (name.endsWith('.xls')) return 'application/vnd.ms-excel'
  if (name.endsWith('.pptx')) return 'application/vnd.openxmlformats-officedocument.presentationml.presentation'
  if (name.endsWith('.txt') || name.endsWith('.md') || name.endsWith('.markdown') || name.endsWith('.log')) return 'text/plain'
  if (name.endsWith('.json')) return 'application/json'
  if (name.endsWith('.csv')) return 'text/csv'
  return 'application/octet-stream'
}

function ensureDataUrlMime(dataUrl, mime) {
  if (!mime || !dataUrl.startsWith('data:')) {
    return dataUrl
  }
  if (/^data:[^;]+;base64,/i.test(dataUrl) && !/^data:;base64,/i.test(dataUrl) && !/^data:application\/octet-stream/i.test(dataUrl)) {
    return dataUrl
  }
  const comma = dataUrl.indexOf(',')
  if (comma < 0) {
    return dataUrl
  }
  return `data:${mime};base64,${dataUrl.slice(comma + 1)}`
}

function isAllowedDoc(file) {
  const name = (file.name || '').toLowerCase()
  return DOC_EXTS.some((ext) => name.endsWith(ext))
}

async function addFiles(fileList) {
  const files = Array.from(fileList || []).filter(Boolean)
  if (!files.length || props.streaming) {
    return
  }
  const room = MAX_ATTACHMENTS - props.draftAttachments.length
  if (room <= 0) {
    notifyWarn(`最多附加 ${MAX_ATTACHMENTS} 个文件`)
    return
  }
  const accepted = []
  let skipped = 0
  for (const file of files) {
    const mime = guessMime(file)
    if (mime.startsWith('image/') || isAllowedDoc(file)) {
      accepted.push(file)
    } else {
      skipped += 1
    }
  }
  if (skipped) {
    notifyWarn(`已忽略 ${skipped} 个不支持的类型（文档仅 pdf/txt/md/docx/xlsx/xls）`)
  }
  const picked = accepted.slice(0, room)
  if (accepted.length > room) {
    notifyWarn(`最多附加 ${MAX_ATTACHMENTS} 个，已忽略多余文件`)
  }
  if (!picked.length) {
    return
  }
  attaching.value = true
  try {
    const next = [...props.draftAttachments]
    for (const file of picked) {
      const mime = guessMime(file)
      let dataUrl = await readAsDataUrl(file)
      dataUrl = ensureDataUrlMime(dataUrl, mime)
      next.push({
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        name: file.name || (mime.startsWith('image/') ? '截图.png' : '附件'),
        dataUrl,
        file,
        kind: mime.startsWith('image/') ? 'image' : 'file'
      })
    }
    emit('update:draftAttachments', next.slice(0, MAX_ATTACHMENTS))
  }
  catch {
    notifyError('读取文件失败')
  }
  finally {
    attaching.value = false
  }
}

function onPickImages(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  addFiles(files)
}

function onPickFiles(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  addFiles(files)
}

function removeAttachment(index) {
  const next = props.draftAttachments.slice()
  next.splice(index, 1)
  emit('update:draftAttachments', next)
}

function onDragEnter(event) {
  event.preventDefault()
  dragDepth += 1
  dragOver.value = true
}

function onDragOver(event) {
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
}

function onDragLeave(event) {
  event.preventDefault()
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) {
    dragOver.value = false
  }
}

function onDrop(event) {
  event.preventDefault()
  dragDepth = 0
  dragOver.value = false
  addFiles(event.dataTransfer?.files)
}

async function onPaste(event) {
  const items = Array.from(event.clipboardData?.items || [])
  const files = items
    .filter((item) => item.kind === 'file')
    .map((item) => item.getAsFile())
    .filter(Boolean)
  if (!files.length) {
    return
  }
  event.preventDefault()
  await addFiles(files)
}

function toolLabel(tool) {
  if (!tool) {
    return ''
  }
  if (typeof tool === 'string') {
    return tool
  }
  return tool.name || tool.label || JSON.stringify(tool)
}
</script>

<template>
  <section class="chat-pane">
    <div ref="scroller" class="transcript">
      <div v-if="messages.length === 0" class="chat-empty">
        <p class="chat-empty-title">有什么可以帮你？</p>
        <p class="field-hint">发消息、拖入图片或文档。当前模型 {{ model || '未选择' }}</p>
      </div>

      <article v-for="(message, index) in messages" :key="index" class="bubble" :class="message.role">
        <div class="bubble-head">
          <span class="role">{{ message.role === 'user' ? '你' : (message.model ? message.model : '助手') }}</span>
          <div class="bubble-actions">
            <button
              v-if="message.content || message.thinking"
              type="button"
              class="icon-btn tiny"
              title="复制"
              @click="copyMessage(message)"
            >
              <el-icon><CopyDocument /></el-icon>
            </button>
          </div>
        </div>
        <div v-if="messageImages(message).length" class="msg-images">
          <img v-for="(img, i) in messageImages(message)" :key="i" :src="img" alt="附件" />
        </div>
        <div v-if="messageFiles(message).length" class="msg-files">
          <span v-for="(file, i) in messageFiles(message)" :key="i" class="file-chip">
            <el-icon><Document /></el-icon>
            {{ file.name }}
          </span>
        </div>
        <div v-if="message.tools && message.tools.length" class="tools">
          <div v-for="(tool, toolIndex) in message.tools" :key="toolIndex" class="tool-row">
            <span class="tool-dot" />
            <span>{{ toolLabel(tool) }}</span>
          </div>
        </div>
        <div v-if="message.thinking" class="thinking">
          <span>思考过程</span>
          <pre>{{ message.thinking }}</pre>
        </div>
        <div v-if="message.sources && message.sources.length" class="sources">
          <button type="button" class="sources-toggle" :aria-expanded="!!openSources[index]" @click="toggleSources(index)">
            搜索到的网页（{{ message.sources.length }}）
            <el-icon :class="{ open: openSources[index] }"><ArrowDown /></el-icon>
          </button>
          <ul v-show="openSources[index]">
            <li v-for="page in message.sources" :key="page.url">
              <a v-if="isHttp(page.url)" :href="page.url" target="_blank" rel="noopener noreferrer">
                <span class="source-title">{{ page.title || page.url }}</span>
                <span class="source-url">{{ page.url }}</span>
              </a>
            </li>
          </ul>
        </div>
        <MarkdownBody
          v-if="message.content"
          class="text"
          :text="message.content"
          :plain="message.role === 'user'"
        />
        <p v-else-if="isPending(message, index)" class="pending-line">
          <span class="pending-pulse" />
          正在执行…
        </p>
      </article>
    </div>

    <el-alert v-if="error" class="chat-error" type="error" :title="error" show-icon :closable="false" />

    <form
      class="composer dock"
      :class="{ 'is-dragover': dragOver }"
      @submit.prevent="emit('send')"
      @dragenter="onDragEnter"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <div v-if="dragOver" class="composer-drop-hint">松开以上传图片或文档</div>
      <div v-if="draftAttachments.length" class="draft-attachments">
        <div
          v-for="(item, i) in draftAttachments"
          :key="item.id || i"
          class="draft-attach"
          :class="{ image: isImageDataUrl(item.dataUrl) }"
        >
          <img v-if="isImageDataUrl(item.dataUrl)" :src="item.dataUrl" :alt="item.name" />
          <span v-else class="file-chip">
            <el-icon><Document /></el-icon>
            {{ item.name }}
          </span>
          <button type="button" title="移除" @click="removeAttachment(i)">×</button>
        </div>
      </div>

      <el-input
        class="dock-input"
        :model-value="draft"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 8 }"
        resize="none"
        placeholder="发消息或创建任务… / 添加资料"
        :disabled="streaming"
        @update:model-value="emit('update:draft', $event)"
        @keydown="onKeydown"
        @paste="onPaste"
      />

      <div class="dock-toolbar">
        <div class="dock-left">
          <el-dropdown ref="attachMenu" trigger="click" :disabled="streaming || attaching">
            <button type="button" class="dock-plus" :disabled="streaming || attaching" title="添加">
              <el-icon :size="18"><Plus /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="imageInput?.click()">图片</el-dropdown-item>
                <el-dropdown-item @click="fileInput?.click()">文件</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <button
            type="button"
            class="dock-pill"
            :class="{ on: think }"
            :disabled="featuresBusy || streaming"
            @click="emit('toggle-feature', 'think', !think)"
          >思考</button>
          <button
            type="button"
            class="dock-pill"
            :class="{ on: webEnabled }"
            :disabled="featuresBusy || streaming"
            @click="emit('toggle-feature', 'webEnabled', !webEnabled)"
          >联网</button>
          <button
            type="button"
            class="dock-pill"
            :class="{ on: ragEnabled }"
            :disabled="featuresBusy || streaming"
            @click="emit('toggle-feature', 'ragEnabled', !ragEnabled)"
          >知识库</button>
          <button
            type="button"
            class="dock-pill"
            :class="{ on: codeEnabled }"
            :disabled="featuresBusy || streaming"
            @click="emit('toggle-feature', 'codeEnabled', !codeEnabled)"
          >代码</button>
        </div>

        <div class="dock-right">
          <button
            type="button"
            class="icon-btn tiny"
            title="重新生成"
            :disabled="!canRegenerate"
            @click="emit('regenerate')"
          >
            <el-icon><RefreshRight /></el-icon>
          </button>
          <button
            type="button"
            class="icon-btn tiny"
            title="导出"
            :disabled="streaming || messages.length === 0"
            @click="emit('export')"
          >
            <el-icon><Download /></el-icon>
          </button>
          <button
            type="button"
            class="icon-btn tiny"
            title="清空"
            :disabled="streaming || messages.length === 0"
            @click="emit('clear')"
          >清</button>
          <button
            v-if="streaming"
            type="button"
            class="dock-send stop"
            title="停止"
            @click="emit('stop')"
          >
            <el-icon :size="16"><VideoPause /></el-icon>
          </button>
          <button
            v-else
            type="submit"
            class="dock-send"
            :disabled="!canSend"
            title="发送"
          >
            <span class="dock-send-arrow" />
          </button>
        </div>
      </div>

      <input ref="imageInput" type="file" accept="image/*" multiple hidden @change="onPickImages" />
      <input ref="fileInput" type="file" :accept="ACCEPT_DOCS" multiple hidden @change="onPickFiles" />
    </form>
  </section>
</template>
