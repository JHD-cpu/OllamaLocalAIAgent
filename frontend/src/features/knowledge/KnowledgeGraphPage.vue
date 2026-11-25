<script setup>
// 独立桌面窗口：三维知识点树全屏展示。
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { listChunks, listDocuments } from '../../api'
import KnowledgeGraph3D from './KnowledgeGraph3D.vue'
import { mergeChunksToKnowledgePoints } from './knowledgePoints'
import { notifyError } from '../../utils/notify.js'

const graphLoading = ref(false)
const graphTree = ref([])
const popupOpen = ref(false)
const popupChunk = ref(null)
let timer = null

onMounted(async () => {
  await loadGraphTree()
  timer = setInterval(() => {
    loadGraphTree()
  }, 4000)
})

onUnmounted(() => {
  clearInterval(timer)
})

watch(
  () => graphTree.value.map((d) => `${d.id}:${(d.children || []).length}`).join('|'),
  () => {
    /* keep reactive for 3D rebuild */
  }
)

async function loadGraphTree() {
  graphLoading.value = true
  try {
    const documents = await listDocuments()
    const ready = documents.filter((d) => d.status === 'READY')
    const trees = []
    for (const doc of ready) {
      const list = await listChunks(doc.id)
      trees.push({
        id: doc.id,
        label: doc.filename,
        children: mergeChunksToKnowledgePoints(list, {
          documentId: doc.id,
          filename: doc.filename
        })
      })
    }
    graphTree.value = trees
  }
  catch (reason) {
    notifyError(reason.message || '加载知识点树失败')
  }
  finally {
    graphLoading.value = false
  }
}

function onSelectGraphChunk(chunk) {
  popupChunk.value = chunk
  popupOpen.value = true
}

/** 通知主窗口打开对应文档分片。 */
function openInMainWindow() {
  const chunk = popupChunk.value
  if (!chunk?.documentId) {
    return
  }
  const payload = {
    type: 'open-knowledge-doc',
    documentId: chunk.documentId,
    chunkId: chunk.id || chunk.chunkIds?.[0] || null,
    filename: chunk.filename || chunk.label || ''
  }
  try {
    const channel = new BroadcastChannel('ollama-knowledge')
    channel.postMessage(payload)
    channel.close()
  } catch {
    /* ignore */
  }
  try {
    localStorage.setItem('ollama-knowledge-nav', JSON.stringify({ ...payload, at: Date.now() }))
  } catch {
    /* ignore */
  }
  popupOpen.value = false
}
</script>

<template>
  <section class="graph-window-pane">
    <div class="graph-window-head">
      <h2>三维知识点树</h2>
      <el-button size="small" text @click="loadGraphTree">刷新</el-button>
    </div>
    <KnowledgeGraph3D
      class="graph-window-canvas"
      :tree="graphTree"
      :loading="graphLoading"
      :show-head="false"
      @select-chunk="onSelectGraphChunk"
    />
    <el-dialog
      v-model="popupOpen"
      :title="popupChunk?.isDocHint ? popupChunk.label : (popupChunk?.label || '知识点')"
      width="720px"
      align-center
      class="knowledge-popup"
    >
      <p v-if="popupChunk && !popupChunk.isDocHint" class="field-hint">
        {{ popupChunk.filename }}
        · 知识点
        · {{ popupChunk.chunkCount ?? 1 }} 个分片
        · {{ popupChunk.charCount ?? 0 }} 字
      </p>
      <pre class="knowledge-popup-text">{{ popupChunk?.text || '' }}</pre>
      <template #footer>
        <el-button @click="popupOpen = false">关闭</el-button>
        <el-button
          v-if="popupChunk?.documentId"
          type="primary"
          @click="openInMainWindow"
        >
          在知识库定位
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>
