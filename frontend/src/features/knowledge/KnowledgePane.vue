<script setup>
// 知识库：文档列表分页 + 分块编辑/原文预览；三维图在独立桌面弹窗。
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { notifyError, notifySuccess, notifyWarn } from '../../utils/notify.js'

const props = defineProps({
  /** 图谱窗口定位：{ documentId, chunkId, at } */
  focus: { type: Object, default: null }
})
import {
  addChunk,
  deleteChunk,
  deleteDocument,
  listChunks,
  listDocuments,
  openKnowledgeGraphWindow,
  originalUrl,
  rechunkDocument,
  updateChunk,
  uploadDocument
} from '../../api'
import ChunkEditorDialog from './ChunkEditorDialog.vue'
import DocumentTable from './DocumentTable.vue'
import OriginalPreviewDialog from './OriginalPreviewDialog.vue'

const documents = ref([])
const error = ref('')
const uploading = ref(false)
const file = ref(null)
const uploadRef = ref(null)
const graphOpening = ref(false)
let timer = null

const page = ref(1)
const pageSize = 8

const originalOpen = ref(false)
const originalName = ref('')
const originalKind = ref('')
const originalSrc = ref('')
const originalText = ref('')
const originalError = ref('')

const chunksOpen = ref(false)
const chunksDoc = ref(null)
const chunks = ref([])
const chunksLoading = ref(false)
const chunkError = ref('')
const newChunk = ref('')
const savingId = ref('')
const chunkPage = ref(1)
const chunkPageSize = 10

const statusText = {
  PROCESSING: '处理中',
  READY: '就绪',
  FAILED: '失败'
}

const statusType = {
  PROCESSING: 'warning',
  READY: 'success',
  FAILED: 'danger'
}

const pagedDocuments = computed(() => {
  const start = (page.value - 1) * pageSize
  return documents.value.slice(start, start + pageSize)
})

const processingCount = computed(
  () => documents.value.filter((item) => item.status === 'PROCESSING').length
)

watch(
  () => props.focus,
  async (target) => {
    if (!target?.documentId) {
      return
    }
    await refresh()
    const row = documents.value.find((d) => d.id === target.documentId)
    if (!row) {
      notifyWarn('未找到对应文档，可能已删除')
      return
    }
    if (row.status !== 'READY') {
      notifyWarn(`「${row.filename}」尚未就绪（${statusText[row.status] || row.status}），稍后再打开分片`)
      return
    }
    const idx = documents.value.findIndex((d) => d.id === target.documentId)
    if (idx >= 0) {
      page.value = Math.floor(idx / pageSize) + 1
    }
    await onChunks(row)
  },
  { deep: true }
)

onMounted(async () => {
  await refresh()
  timer = setInterval(() => {
    if (documents.value.some((item) => item.status === 'PROCESSING')) {
      refresh()
    }
  }, 1000)
})

onUnmounted(() => {
  clearInterval(timer)
})

const notifiedFailures = new Set()

watch(error, (value) => {
  if (value) {
    notifyError(value)
  }
})

watch(
  documents,
  (list) => {
    for (const doc of list || []) {
      if (doc.status !== 'FAILED' || !doc.errorMessage) {
        continue
      }
      const key = `${doc.id}:${doc.errorMessage}`
      if (notifiedFailures.has(key)) {
        continue
      }
      notifiedFailures.add(key)
      notifyError(`「${doc.filename}」处理失败：${doc.errorMessage}`)
    }
  },
  { deep: true }
)

watch(
  () => documents.value.length,
  (len) => {
    const pages = Math.max(1, Math.ceil(len / pageSize))
    if (page.value > pages) {
      page.value = pages
    }
  }
)

async function refresh() {
  try {
    documents.value = await listDocuments()
    error.value = ''
  }
  catch (reason) {
    error.value = reason.message
  }
}

function openGraphFallback() {
  window.open('/?view=knowledge-graph', 'knowledge-graph', 'width=1120,height=760,menubar=no,toolbar=no')
}

async function onOpenGraph() {
  if (graphOpening.value) {
    return
  }
  graphOpening.value = true
  try {
    const result = await openKnowledgeGraphWindow()
    if (!result?.desktop) {
      openGraphFallback()
    }
    else {
      notifySuccess('已请求打开三维知识点树窗口')
    }
  }
  catch (reason) {
    openGraphFallback()
    notifyError(reason.message || '打开图谱窗口失败，已尝试浏览器弹窗')
  }
  finally {
    graphOpening.value = false
  }
}

function onFileChange(uploadFile) {
  file.value = uploadFile?.raw || null
}

function onFileRemove() {
  file.value = null
}

async function onUpload() {
  if (!file.value || uploading.value) {
    return
  }
  uploading.value = true
  error.value = ''
  try {
    await uploadDocument(file.value)
    file.value = null
    uploadRef.value?.clearFiles()
    await refresh()
  }
  catch (reason) {
    error.value = reason.message
  }
  finally {
    uploading.value = false
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm('原文、切块和向量会一起去掉。', `删除「${row.filename}」？`, {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  }
  catch {
    return
  }
  error.value = ''
  try {
    await deleteDocument(row.id)
    await refresh()
  }
  catch (reason) {
    error.value = reason.message
  }
}

async function onRechunk(row) {
  try {
    await ElMessageBox.confirm('将按当前规则重新分块，旧切块与向量会被替换，原文件保留。', `重新分块「${row.filename}」？`, {
      type: 'warning',
      confirmButtonText: '重新分块',
      cancelButtonText: '取消'
    })
  }
  catch {
    return
  }
  error.value = ''
  try {
    await rechunkDocument(row.id)
    notifySuccess(`「${row.filename}」已开始重新分块`)
    await refresh()
  }
  catch (reason) {
    error.value = reason.message
  }
}

async function onOriginal(row) {
  originalName.value = row.filename
  originalKind.value = row.mediaType
  originalSrc.value = originalUrl(row.id, row.filename)
  originalText.value = ''
  originalError.value = ''
  originalOpen.value = true
  if (row.mediaType === 'pdf') {
    return
  }
  try {
    const response = await fetch(originalSrc.value)
    if (!response.ok) {
      throw new Error(await response.text())
    }
    originalText.value = await response.text()
  }
  catch (reason) {
    originalError.value = reason.message || '无法打开原文件'
  }
}

async function onChunks(row) {
  chunksDoc.value = row
  chunks.value = []
  chunkPage.value = 1
  newChunk.value = ''
  chunkError.value = ''
  chunksOpen.value = true
  await loadChunks()
}

async function loadChunks() {
  if (!chunksDoc.value) {
    return
  }
  chunksLoading.value = true
  chunkError.value = ''
  try {
    const list = await listChunks(chunksDoc.value.id)
    chunks.value = list.map((item) => ({ ...item, draft: item.text }))
    const pages = Math.max(1, Math.ceil(chunks.value.length / chunkPageSize))
    if (chunkPage.value > pages) {
      chunkPage.value = pages
    }
    await refresh()
  }
  catch (reason) {
    chunkError.value = reason.message
  }
  finally {
    chunksLoading.value = false
  }
}

async function onSaveChunk(chunk) {
  const text = chunk.draft.trim()
  if (!text || text === chunk.text || savingId.value) {
    return
  }
  savingId.value = chunk.id
  chunkError.value = ''
  try {
    const saved = await updateChunk(chunksDoc.value.id, chunk.id, text)
    chunk.text = saved.text
    chunk.draft = saved.text
    chunk.charCount = saved.charCount
  }
  catch (reason) {
    chunkError.value = reason.message
  }
  finally {
    savingId.value = ''
  }
}

async function onDeleteChunk(chunk) {
  try {
    await ElMessageBox.confirm('这块正文和对应向量会一起去掉。', `删除第 ${chunk.index + 1} 块？`, {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  }
  catch {
    return
  }
  chunkError.value = ''
  try {
    await deleteChunk(chunksDoc.value.id, chunk.id)
    await loadChunks()
  }
  catch (reason) {
    chunkError.value = reason.message
  }
}

async function onAddChunk() {
  const text = newChunk.value.trim()
  if (!text || savingId.value) {
    return
  }
  savingId.value = 'new'
  chunkError.value = ''
  try {
    await addChunk(chunksDoc.value.id, text)
    newChunk.value = ''
    await loadChunks()
    chunkPage.value = Math.max(1, Math.ceil(chunks.value.length / chunkPageSize))
  }
  catch (reason) {
    chunkError.value = reason.message
  }
  finally {
    savingId.value = ''
  }
}
</script>

<template>
  <section class="pane">
    <div>
      <h2>知识库</h2>
      <p class="field-hint">
        支持 txt、md、pdf、docx、xlsx，上传不限大小。入库先粗分片再按「主题→子主题→概念」细切；扫描 PDF 用本地 Ollama 视觉模型（无则默认模型）。多文档可并行分块。
      </p>
    </div>

    <div class="knowledge-bar">
      <el-upload
        ref="uploadRef"
        class="knowledge-upload"
        :auto-upload="false"
        :limit="1"
        accept=".pdf,.txt,.md,.docx,.xlsx,.xls,application/pdf,text/plain,text/markdown,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
      >
        <el-button size="small">选择文件</el-button>
      </el-upload>
      <el-button size="small" type="primary" :disabled="!file" :loading="uploading" @click="onUpload">上传</el-button>
      <el-button size="small" text @click="refresh">刷新</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <el-alert
      v-if="processingCount > 0"
      type="warning"
      :title="`有 ${processingCount} 个文档正在入库，就绪前对话检索可能搜不到其内容`"
      show-icon
      :closable="false"
      class="knowledge-processing-hint"
    />

    <div class="knowledge-list-block">
      <div class="knowledge-list-head">
        <h3>文档列表</h3>
        <div class="knowledge-list-actions">
          <el-button size="small" type="primary" plain :loading="graphOpening" @click="onOpenGraph">
            三维知识点树
          </el-button>
          <el-pagination
            v-if="documents.length > pageSize"
            :current-page="page"
            :page-size="pageSize"
            :total="documents.length"
            layout="prev, pager, next, total"
            background
            size="small"
            @current-change="page = $event"
          />
        </div>
      </div>
      <DocumentTable
        :documents="pagedDocuments"
        :status-text="statusText"
        :status-type="statusType"
        @original="onOriginal"
        @chunks="onChunks"
        @rechunk="onRechunk"
        @delete="onDelete"
      />
    </div>

    <OriginalPreviewDialog
      v-model="originalOpen"
      :name="originalName"
      :kind="originalKind"
      :src="originalSrc"
      :text="originalText"
      :error="originalError"
    />

    <ChunkEditorDialog
      v-model="chunksOpen"
      :doc="chunksDoc"
      :chunks="chunks"
      :loading="chunksLoading"
      :error="chunkError"
      :saving-id="savingId"
      :new-chunk="newChunk"
      :page="chunkPage"
      :page-size="chunkPageSize"
      @update:page="chunkPage = $event"
      @update:new-chunk="newChunk = $event"
      @save="onSaveChunk"
      @delete="onDeleteChunk"
      @add="onAddChunk"
    />
  </section>
</template>
