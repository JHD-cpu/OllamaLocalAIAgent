<script setup>
// 知识库页：上传文档、列表刷新、原文预览与分块编辑。
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  addChunk,
  deleteChunk,
  deleteDocument,
  listChunks,
  listDocuments,
  originalUrl,
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
let timer = null

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

// 进入页面拉取列表；处理中的文档每 2 秒轮询刷新。
onMounted(async () => {
  await refresh()
  timer = setInterval(() => {
    if (documents.value.some((item) => item.status === 'PROCESSING')) {
      refresh()
    }
  }, 2000)
})

// 离开页面清除轮询定时器。
onUnmounted(() => {
  clearInterval(timer)
})

/** 从后端重新加载文档列表。 */
async function refresh() {
  try {
    documents.value = await listDocuments()
  }
  catch (reason) {
    error.value = reason.message
  }
}

/** 记录 el-upload 选中的原始 File 对象。 */
function onFileChange(uploadFile) {
  file.value = uploadFile?.raw || null
}

/** 清空待上传文件引用。 */
function onFileRemove() {
  file.value = null
}

/** 上传选中文件并刷新列表。 */
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

/** 确认后删除文档及其向量数据。 */
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

/** 打开原文预览：PDF 用 iframe，文本类拉取正文。 */
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

/** 打开分块编辑弹窗并加载该文档切块。 */
async function onChunks(row) {
  chunksDoc.value = row
  chunks.value = []
  chunkPage.value = 1
  newChunk.value = ''
  chunkError.value = ''
  chunksOpen.value = true
  await loadChunks()
}

/** 拉取当前文档分块并为每条附加可编辑 draft。 */
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

/** 将单块 draft 保存到后端。 */
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

/** 确认后删除单个分块。 */
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

/** 新增一段正文为分块并跳转到末页。 */
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
      <p class="field-hint">支持 txt、md 和 pdf。文字按语义切块后写入本地知识库。扫描版 PDF 需要本机已安装 Python 和 PaddleOCR（pip install paddleocr）；没有安装时，上传扫描件会提示你先安装。</p>
    </div>

    <div class="knowledge-bar">
      <el-upload
        ref="uploadRef"
        class="knowledge-upload"
        :auto-upload="false"
        :limit="1"
        accept=".pdf,.txt,.md,application/pdf,text/plain,text/markdown"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
      >
        <el-button>选择文件</el-button>
      </el-upload>
      <el-button type="primary" :disabled="!file" :loading="uploading" @click="onUpload">上传</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />

    <DocumentTable
      :documents="documents"
      :status-text="statusText"
      :status-type="statusType"
      @original="onOriginal"
      @chunks="onChunks"
      @delete="onDelete"
    />

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
