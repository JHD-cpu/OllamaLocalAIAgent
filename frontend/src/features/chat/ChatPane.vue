<script setup>
// 对话区：消息列表、输入框、功能开关与自动滚动。
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'

const props = defineProps({
  messages: { type: Array, required: true },
  draft: { type: String, required: true },
  streaming: { type: Boolean, required: true },
  error: { type: String, default: '' },
  model: { type: String, default: '' },
  think: { type: Boolean, default: false },
  webEnabled: { type: Boolean, default: false },
  ragEnabled: { type: Boolean, default: false },
  codeEnabled: { type: Boolean, default: false },
  featuresBusy: { type: Boolean, default: false }
})

const emit = defineEmits(['update:draft', 'send', 'stop', 'clear', 'toggle-feature'])

const scroller = ref(null)
const openSources = ref({})
// 有非空草稿且未在流式生成时可发送。
const canSend = computed(() => props.draft.trim().length > 0 && !props.streaming)

/** 展开或收起某条助手消息的网页来源列表。 */
function toggleSources(index) {
  openSources.value = { ...openSources.value, [index]: !openSources.value[index] }
}

/** 判断是否为可外链的 http(s) URL。 */
function isHttp(url) {
  return typeof url === 'string' && /^https?:\/\//i.test(url)
}

// 消息或流式内容变化后滚到底部。deep 是因为正文是在原对象上追加的。
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

// Enter 发送，Shift+Enter 留给输入框换行。
function onKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    if (canSend.value) {
      emit('send')
    }
  }
}

// 助手气泡还没有任何正文时，显示「正在生成」。思考过程和工具进度不算正文。
function isPending(message, index) {
  return props.streaming && index === props.messages.length - 1 && message.role === 'assistant' && !message.content
}
</script>

<template>
  <section class="pane">
    <div ref="scroller" class="transcript">
      <el-empty v-if="messages.length === 0" :description="`和 ${model || 'Ollama'} 对话`">
        <p class="field-hint">消息会带着当前保存的系统提示词和采样参数发送。第一次提问会先把模型载入内存，可能要等一会儿。</p>
      </el-empty>

      <article v-for="(message, index) in messages" :key="index" class="bubble" :class="message.role">
        <span class="role">{{ message.role === 'user' ? '你' : (message.model ? `模型 · ${message.model}` : '模型') }}</span>
        <!-- 一条助手消息可以同时有：联网进度、思考过程、搜到的网页、正文。 -->
        <div v-if="message.tools && message.tools.length" class="tools">
          <el-tag v-for="(tool, toolIndex) in message.tools" :key="toolIndex" size="small" type="info">{{ tool }}</el-tag>
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
        <p v-if="message.content" class="text">{{ message.content }}</p>
        <p v-else-if="isPending(message, index)" class="field-hint">正在生成…</p>
      </article>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />

    <form class="composer" @submit.prevent="emit('send')">
      <el-input
        :model-value="draft"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="输入消息，Enter 发送，Shift + Enter 换行"
        :disabled="streaming"
        @update:model-value="emit('update:draft', $event)"
        @keydown="onKeydown"
      />
      <div class="composer-features">
        <el-button
          size="small"
          round
          :type="think ? 'primary' : 'default'"
          :disabled="featuresBusy || streaming"
          @click="emit('toggle-feature', 'think', !think)"
        >深度思考</el-button>
        <el-button
          size="small"
          round
          :type="webEnabled ? 'primary' : 'default'"
          :disabled="featuresBusy || streaming"
          @click="emit('toggle-feature', 'webEnabled', !webEnabled)"
        >联网</el-button>
        <el-button
          size="small"
          round
          :type="ragEnabled ? 'success' : 'default'"
          :disabled="featuresBusy || streaming"
          @click="emit('toggle-feature', 'ragEnabled', !ragEnabled)"
        >知识库</el-button>
        <el-button
          size="small"
          round
          :type="codeEnabled ? 'warning' : 'default'"
          :disabled="featuresBusy || streaming"
          @click="emit('toggle-feature', 'codeEnabled', !codeEnabled)"
        >代码生成</el-button>
      </div>
      <div class="composer-actions">
        <el-button :disabled="streaming || messages.length === 0" @click="emit('clear')">清空对话</el-button>
        <!-- 生成中把「发送」换成「停止」，避免同一条消息发两次。 -->
        <el-button v-if="streaming" type="danger" @click="emit('stop')">停止</el-button>
        <el-button v-else type="primary" native-type="submit" :disabled="!canSend">发送</el-button>
      </div>
    </form>
  </section>
</template>
