<script setup>
// 分块编辑弹窗：分页展示、保存/删除与新增切块。
import { computed } from 'vue'

const props = defineProps({
  doc: { type: Object, default: null },
  chunks: { type: Array, required: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  savingId: { type: String, default: '' },
  newChunk: { type: String, default: '' },
  page: { type: Number, required: true },
  pageSize: { type: Number, required: true }
})

const open = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['update:page', 'update:newChunk', 'save', 'delete', 'add'])

// 按当前页码切片，只渲染一页分块。
const visibleChunks = computed(() => {
  const start = (props.page - 1) * props.pageSize
  return props.chunks.slice(start, start + props.pageSize)
})
</script>

<template>
  <el-dialog v-model="open" :title="doc ? `${doc.filename} 的分块` : '分块'" width="760px" align-center>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <div v-loading="loading" class="chunk-list">
      <el-pagination
        v-if="chunks.length > pageSize"
        :current-page="page"
        :page-size="pageSize"
        :total="chunks.length"
        layout="prev, pager, next, total"
        background
        size="small"
        @current-change="emit('update:page', $event)"
      />
      <article v-for="chunk in visibleChunks" :key="chunk.id" class="chunk-card">
        <div class="chunk-head">
          <span>第 {{ chunk.index + 1 }} 块 · {{ chunk.charCount }} 字</span>
          <div>
            <el-button link type="primary" :disabled="!chunk.draft.trim() || chunk.draft.trim() === chunk.text" :loading="savingId === chunk.id" @click="emit('save', chunk)">保存</el-button>
            <el-button link type="danger" @click="emit('delete', chunk)">删除</el-button>
          </div>
        </div>
        <el-input v-model="chunk.draft" type="textarea" :rows="4" resize="vertical" />
      </article>
      <p v-if="!loading && chunks.length === 0" class="field-hint">还没有分块。</p>
      <article class="chunk-card">
        <div class="chunk-head">
          <span>新增分块</span>
          <el-button link type="primary" :disabled="!newChunk.trim()" :loading="savingId === 'new'" @click="emit('add')">添加</el-button>
        </div>
        <el-input :model-value="newChunk" type="textarea" :rows="3" resize="vertical" placeholder="写一段要加入知识库的文字" @update:model-value="emit('update:newChunk', $event)" />
      </article>
    </div>
  </el-dialog>
</template>
