<script setup>
// 知识库文档表格：状态标签与操作按钮。
defineProps({
  documents: { type: Array, required: true },
  statusText: { type: Object, required: true },
  statusType: { type: Object, required: true }
})

defineEmits(['original', 'chunks', 'delete'])

/** 将 ISO 时间格式化为界面显示的简短日期时间。 */
function when(value) {
  if (!value) {
    return ''
  }
  return String(value).replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <el-table :data="documents" empty-text="还没有资料" stripe>
    <el-table-column prop="filename" label="文件" min-width="220">
      <template #default="{ row }">
        <div>{{ row.filename }}</div>
        <p v-if="row.errorMessage" class="field-hint">{{ row.errorMessage }}</p>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="100">
      <template #default="{ row }">
        <el-tag :type="statusType[row.status] || 'info'" size="small">{{ statusText[row.status] || row.status }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="块数" width="80">
      <template #default="{ row }">{{ row.chunkCount ?? '—' }}</template>
    </el-table-column>
    <el-table-column label="时间" width="160">
      <template #default="{ row }">{{ when(row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="" width="210" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="$emit('original', row)">原文</el-button>
        <el-button link type="primary" :disabled="row.status !== 'READY'" @click="$emit('chunks', row)">分块</el-button>
        <el-button link type="danger" @click="$emit('delete', row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>
