<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  ChatDotRound,
  Collection,
  EditPen,
  Expand,
  Fold,
  Plus,
  Setting
} from '@element-plus/icons-vue'
import ChatPane from './features/chat/ChatPane.vue'
import SessionSidebar from './features/chat/SessionSidebar.vue'
import KnowledgeGraphPage from './features/knowledge/KnowledgeGraphPage.vue'
import KnowledgePane from './features/knowledge/KnowledgePane.vue'
import SettingsPanel from './features/settings/SettingsPanel.vue'
import { getLogInfo } from './api'
import { useChatSession } from './composables/useChatSession.js'
import { useOllamaInfo } from './composables/useOllamaInfo.js'
import { useSettings } from './composables/useSettings.js'
import { notifyError, notifySuccess } from './utils/notify.js'

function resolveInitialView() {
  try {
    const q = new URLSearchParams(window.location.search)
    if (q.get('view') === 'knowledge-graph') {
      return 'knowledge-graph'
    }
  }
  catch {
    /* ignore */
  }
  return 'chat'
}

const view = ref(resolveInitialView())
const graphOnly = computed(() => view.value === 'knowledge-graph')
const sidebarOpen = ref(false)
const logPath = ref('')
const {
  form,
  saved,
  loaded,
  loadError,
  models,
  providers,
  settingsError,
  probeMessage,
  settingsOpen,
  saving,
  probing,
  dirty,
  applySettings,
  load,
  save,
  probe,
  reset,
  syncWebPrompt,
  applyProviderPreset
} = useSettings()

const { info, statusText, refreshInfo } = useOllamaInfo(models)
const {
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
  exportMarkdown
} = useChatSession({
  form,
  applySettings,
  syncWebPrompt
})

const currentTitle = computed(() => {
  if (view.value === 'knowledge') {
    return '知识库'
  }
  const hit = sessions.value.find((s) => s.id === sessionId.value)
  return hit?.title || '新对话'
})

watch(loadError, (value) => {
  if (value) {
    notifyError(value)
  }
})
watch(settingsError, (value) => {
  if (value) {
    notifyError(value)
  }
})
watch(chatError, (value) => {
  if (value) {
    notifyError(value)
  }
})

const knowledgeFocus = ref(null)
let knowledgeChannel = null

function onKnowledgeNav(payload) {
  if (!payload || payload.type !== 'open-knowledge-doc' || !payload.documentId) {
    return
  }
  knowledgeFocus.value = {
    documentId: payload.documentId,
    chunkId: payload.chunkId || null,
    at: Date.now()
  }
  view.value = 'knowledge'
}

onMounted(async () => {
  try {
    await load()
    if (!graphOnly.value) {
      await refreshInfo()
      await loadSessions()
      try {
        knowledgeChannel = new BroadcastChannel('ollama-knowledge')
        knowledgeChannel.onmessage = (event) => onKnowledgeNav(event.data)
      } catch {
        /* ignore */
      }
      try {
        const raw = localStorage.getItem('ollama-knowledge-nav')
        if (raw) {
          const parsed = JSON.parse(raw)
          if (parsed?.at && Date.now() - parsed.at < 15000) {
            onKnowledgeNav(parsed)
          }
          localStorage.removeItem('ollama-knowledge-nav')
        }
      } catch {
        /* ignore */
      }
    }
    try {
      const log = await getLogInfo()
      logPath.value = log?.path || ''
    }
    catch {
      /* ignore */
    }
  }
  catch (error) {
    loadError.value = error.message
    info.value = { ok: false, version: '', message: error.message, models: [] }
  }
})

onUnmounted(() => {
  knowledgeChannel?.close()
})

async function onSave() {
  try {
    await save()
    await refreshInfo()
    notifySuccess('配置已保存')
  }
  catch {
    /* settingsError already set */
  }
}

async function onProbe() {
  try {
    await probe()
  }
  catch {
    /* settingsError already set */
  }
}

function onExport() {
  const text = exportMarkdown()
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `chat-${sessionId.value || 'export'}.md`
  a.click()
  URL.revokeObjectURL(url)
  notifySuccess('已导出 Markdown')
}

function onSelectSession(id) {
  selectSession(id)
  sidebarOpen.value = false
}

function onCreateSession() {
  onNewSession()
  sidebarOpen.value = false
  view.value = 'chat'
}
</script>

<template>
  <div class="shell agent-shell" :class="{ 'graph-only-shell': graphOnly }">
    <header v-if="!graphOnly" class="agent-top">
      <div class="agent-top-left">
        <button
          type="button"
          class="icon-btn"
          :title="sidebarOpen ? '收起会话' : '会话列表'"
          @click="sidebarOpen = !sidebarOpen"
        >
          <el-icon :size="18"><component :is="sidebarOpen ? Fold : Expand" /></el-icon>
        </button>
        <button type="button" class="icon-btn" title="新对话" :disabled="streaming" @click="onCreateSession">
          <el-icon :size="18"><EditPen /></el-icon>
        </button>
      </div>

      <div class="agent-top-center">
        <h1 class="agent-title">{{ currentTitle }}</h1>
        <p class="agent-disclaimer">AI 生成内容可能有误，请核实 · {{ statusText }}</p>
      </div>

      <div class="agent-top-right">
        <div class="view-switch">
          <button
            type="button"
            class="view-chip"
            :class="{ on: view === 'chat' }"
            @click="view = 'chat'"
          >
            <el-icon><ChatDotRound /></el-icon>
            对话
          </button>
          <button
            type="button"
            class="view-chip"
            :class="{ on: view === 'knowledge' }"
            @click="view = 'knowledge'"
          >
            <el-icon><Collection /></el-icon>
            知识库
          </button>
        </div>
        <button type="button" class="icon-btn" title="配置" :disabled="!loaded" @click="settingsOpen = true">
          <el-icon :size="18"><Setting /></el-icon>
        </button>
      </div>
    </header>

    <div
      v-if="!graphOnly && sidebarOpen"
      class="sidebar-mask"
      @click="sidebarOpen = false"
    />
    <aside v-if="!graphOnly" class="agent-sidebar" :class="{ open: sidebarOpen }">
      <div class="agent-sidebar-head">
        <span>会话</span>
        <button type="button" class="icon-btn" title="新建" :disabled="streaming" @click="onCreateSession">
          <el-icon><Plus /></el-icon>
        </button>
      </div>
      <SessionSidebar
        :sessions="sessions"
        :session-id="sessionId"
        :loading="sessionsLoading"
        :disabled="streaming"
        @select="onSelectSession"
        @create="onCreateSession"
        @rename="onRenameSession"
        @delete="onDeleteSession"
      />
    </aside>

    <main class="agent-body" :class="{ 'graph-only-body': graphOnly }">
      <el-alert v-if="loadError" type="error" :title="loadError" show-icon :closable="false" />
      <template v-else-if="loaded">
        <KnowledgeGraphPage v-if="graphOnly" />
        <ChatPane
          v-else-if="view === 'chat'"
          :messages="messages"
          :draft="draft"
          :draft-attachments="draftAttachments"
          :streaming="streaming"
          :error="chatError"
          :model="saved.model"
          :think="!!saved.think"
          :web-enabled="!!saved.webEnabled"
          :rag-enabled="!!saved.ragEnabled"
          :code-enabled="!!saved.codeEnabled"
          :features-busy="featuresBusy"
          @update:draft="draft = $event"
          @update:draft-attachments="draftAttachments = $event"
          @send="onSend"
          @stop="onStop"
          @clear="onClear"
          @toggle-feature="onToggleFeature"
          @regenerate="onRegenerate"
          @export="onExport"
        />
        <KnowledgePane v-else :focus="knowledgeFocus" />
      </template>
    </main>
  </div>

  <SettingsPanel
    v-if="!graphOnly"
    v-model="settingsOpen"
    :form="form"
    :models="models"
    :providers="providers"
    :dirty="dirty"
    :saving="saving"
    :probing="probing"
    :error="settingsError"
    :probe-message="probeMessage"
    :log-path="logPath"
    @save="onSave"
    @probe="onProbe"
    @reset="reset"
    @models-changed="refreshInfo"
    @provider-change="applyProviderPreset"
  />
</template>
