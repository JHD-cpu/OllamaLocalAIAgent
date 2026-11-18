<script setup>
// 原文预览弹窗：PDF iframe 或纯文本 pre。
defineProps({
  name: { type: String, default: '' },
  kind: { type: String, default: '' },
  src: { type: String, default: '' },
  text: { type: String, default: '' },
  error: { type: String, default: '' }
})

const open = defineModel({ type: Boolean, default: false })
</script>

<template>
  <el-dialog v-model="open" :title="name" width="860px" align-center>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <iframe v-else-if="kind === 'pdf'" class="original-frame" :src="src" title="原文件" />
    <pre v-else class="original-text">{{ text }}</pre>
  </el-dialog>
</template>
