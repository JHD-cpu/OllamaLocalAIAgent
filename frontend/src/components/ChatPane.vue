<script setup>
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps({
  messages: { type: Array, required: true },
  draft: { type: String, required: true },
  streaming: { type: Boolean, required: true },
  error: { type: String, default: '' },
  model: { type: String, default: '' }
})

const emit = defineEmits(['update:draft', 'send', 'stop', 'clear'])

const scroller = ref(null)
const canSend = computed(() => props.draft.trim().length > 0 && !props.streaming)

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

// Enter 发送，Shift+Enter 留给浏览器换行。
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
  <section class="chat">
    <div ref="scroller" class="transcript">
      <div v-if="messages.length === 0" class="empty">
        <p class="eyebrow">本地模型</p>
        <h2>和 {{ model || 'Ollama' }} 对话</h2>
        <p>消息会带着当前保存的系统提示词和采样参数发送。第一次提问会先把模型载入内存，可能要等一会儿。</p>
      </div>

      <article v-for="(message, index) in messages" :key="index" class="bubble" :class="message.role">
        <span class="role">{{ message.role === 'user' ? '你' : '模型' }}</span>
        <!-- 一条助手消息可以同时有：联网进度、思考过程、正文。 -->
        <ul v-if="message.tools && message.tools.length" class="tools">
          <li v-for="(tool, toolIndex) in message.tools" :key="toolIndex">{{ tool }}</li>
        </ul>
        <div v-if="message.thinking" class="thinking">
          <span>思考过程</span>
          <pre>{{ message.thinking }}</pre>
        </div>
        <p v-if="message.content" class="text">{{ message.content }}</p>
        <p v-else-if="isPending(message, index)" class="pending">正在生成…</p>
      </article>
    </div>

    <p v-if="error" class="banner">{{ error }}</p>

    <form class="composer" @submit.prevent="emit('send')">
      <textarea
        :value="draft"
        rows="3"
        placeholder="输入消息，Enter 发送，Shift + Enter 换行"
        :disabled="streaming"
        @input="emit('update:draft', $event.target.value)"
        @keydown="onKeydown"
      />
      <div class="composer-actions">
        <button type="button" class="ghost" :disabled="streaming || messages.length === 0" @click="emit('clear')">
          清空对话
        </button>
        <!-- 生成中把「发送」换成「停止」，避免同一条消息发两次。 -->
        <button v-if="streaming" type="button" class="stop" @click="emit('stop')">停止</button>
        <button v-else type="submit" class="primary" :disabled="!canSend">发送</button>
      </div>
    </form>
  </section>
</template>
