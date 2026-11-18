<script setup>
// 知识库文档表格：状态、进度百分比、分块数与操作。
defineProps({
  documents: { type: Array, required: true },
  statusText: { type: Object, required: true },
  statusType: { type: Object, required: true }
})

defineEmits(['original', 'chunks', 'rechunk', 'delete'])

function when(value) {
  if (!value) {
    return ''
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

function progressOf(row) {
  if (row.status === 'READY') {
    return 100
  }
  if (row.status !== 'PROCESSING') {
    return null
  }
  const n = Number(row.progress)
  if (!Number.isFinite(n)) {
    return 0
  }
  return Math.max(0, Math.min(100, Math.round(n)))
}

function chunkLabel(row) {
  const n = row.chunkCount
  if (n == null || n === '') {
    return row.status === 'PROCESSING' ? '0' : '—'
  }
  return String(n)
}
</script>

<template>
  <el-table class="knowledge-doc-table" :data="documents" empty-text="还没有资料">
    <el-table-column prop="filename" label="文件" min-width="220">
      <template #default="{ row }">
        <div>{{ row.filename }}</div>
        <p v-if="row.errorMessage && row.status === 'FAILED'" class="field-hint">{{ row.errorMessage }}</p>
        <p v-else-if="row.status === 'PROCESSING' && row.progressMessage" class="field-hint">{{ row.progressMessage }}</p>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="150">
      <template #default="{ row }">
        <div class="doc-status">
          <el-tag :type="statusType[row.status] || 'info'" size="small">{{ statusText[row.status] || row.status }}</el-tag>
          <template v-if="row.status === 'PROCESSING'">
            <el-progress
              :percentage="progressOf(row) ?? 0"
              :stroke-width="10"
              :show-text="true"
              style="width: 100%; margin-top: 6px"
            />
          </template>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="分块数" width="90" align="center">
      <template #default="{ row }">
        <strong>{{ chunkLabel(row) }}</strong>
      </template>
    </el-table-column>
    <el-table-column label="时间" width="160">
      <template #default="{ row }">{{ when(row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="" width="280" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="$emit('original', row)">原文</el-button>
        <el-button link type="primary" :disabled="row.status !== 'READY'" @click="$emit('chunks', row)">分块</el-button>
        <el-button
          link
          type="primary"
          :disabled="row.status === 'PROCESSING'"
          @click="$emit('rechunk', row)"
        >重新分块</el-button>
        <el-button link type="danger" @click="$emit('delete', row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.doc-status {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}

:deep(.el-table__body tr > td.el-table__cell) {
  background-color: rgba(10, 18, 32, 0.72) !important;
}

:deep(.el-table__body tr:hover > td.el-table__cell) {
  background-color: rgba(16, 28, 48, 0.92) !important;
}

:deep(.el-table__body tr.el-table__row--striped > td.el-table__cell) {
  background-color: rgba(10, 18, 32, 0.72) !important;
}
</style>
