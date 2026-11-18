<script setup>
import { computed, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Delete, EditPen } from '@element-plus/icons-vue'

const props = defineProps({
  sessions: { type: Array, required: true },
  sessionId: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'create', 'rename', 'delete'])

const filter = ref('')

const filtered = computed(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) {
    return props.sessions
  }
  return props.sessions.filter((s) => String(s.title || '').toLowerCase().includes(q))
})

async function onRename(session) {
  try {
    const { value } = await ElMessageBox.prompt('会话标题', '重命名', {
      inputValue: session.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    if (value && value.trim()) {
      emit('rename', session.id, value.trim())
    }
  }
  catch {
    /* cancel */
  }
}

async function onDelete(session) {
  try {
    await ElMessageBox.confirm(`删除「${session.title}」？`, '删除会话', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
    emit('delete', session.id)
  }
  catch {
    /* cancel */
  }
}
</script>

<template>
  <div class="session-sidebar">
    <el-input v-model="filter" clearable size="small" placeholder="搜索会话" class="session-filter" />
    <div v-loading="loading" class="session-list">
      <button
        v-for="session in filtered"
        :key="session.id"
        type="button"
        class="session-item"
        :class="{ active: session.id === sessionId }"
        :disabled="disabled"
        @click="emit('select', session.id)"
      >
        <span class="session-title">{{ session.title || '新对话' }}</span>
        <span class="session-actions" @click.stop>
          <el-button :icon="EditPen" text size="small" :disabled="disabled" @click="onRename(session)" />
          <el-button :icon="Delete" text size="small" :disabled="disabled" @click="onDelete(session)" />
        </span>
      </button>
      <el-empty v-if="!loading && filtered.length === 0" description="暂无会话" :image-size="56" />
    </div>
  </div>
</template>
