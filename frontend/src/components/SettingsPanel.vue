<script setup>
import { computed } from 'vue'

const props = defineProps({
  form: { type: Object, required: true },
  models: { type: Array, required: true },
  dirty: { type: Boolean, required: true },
  saving: { type: Boolean, required: true },
  probing: { type: Boolean, required: true },
  error: { type: String, default: '' },
  probeMessage: { type: String, default: '' }
})

defineEmits(['save', 'probe', 'reset'])

// 当前输入的模型名如果在已安装列表里，就显示它的参数量和体积。
const selected = computed(() => props.models.find((model) => model.name === props.form.model))

// 后端给的是字节，界面上显示成 GB。
function formatSize(size) {
  if (!size) {
    return ''
  }
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}
</script>

<template>
  <aside class="settings">
    <div class="settings-head">
      <h2>配置</h2>
      <span v-if="dirty" class="dirty">未保存</span>
    </div>
    <p class="hint">保存后才会用于下一次对话。配置写在后端的 data/settings.json。</p>

    <label>
      <span>Ollama 地址</span>
      <input v-model="form.baseUrl" type="url" spellcheck="false" placeholder="http://127.0.0.1:11434" />
    </label>

    <label>
      <span>模型</span>
      <!-- 可以选已安装的模型，也可以手写一个还没出现在列表里的名称。 -->
      <input v-model="form.model" list="model-list" spellcheck="false" placeholder="qwen3.5:9b" />
      <datalist id="model-list">
        <option v-for="model in models" :key="model.name" :value="model.name" />
      </datalist>
    </label>
    <p v-if="selected" class="meta">
      {{ selected.parameterSize }} · {{ selected.quantization }} · {{ formatSize(selected.size) }}
      <template v-if="selected.contextLength"> · 上下文上限 {{ selected.contextLength }}</template>
    </p>

    <label>
      <span>系统提示词</span>
      <textarea v-model="form.systemPrompt" rows="5" placeholder="给模型的角色说明" />
    </label>

    <label class="check">
      <input v-model="form.think" type="checkbox" />
      <span>思考模式</span>
    </label>
    <p class="hint">适合推理题。开启后会先输出思考过程，速度更慢。</p>

    <label class="check">
      <input v-model="form.webEnabled" type="checkbox" />
      <span>允许联网</span>
    </label>
    <p class="hint">开启后，模型可以搜索网页。打开网页时不会访问本机和内网地址。</p>
    <div class="grid">
      <label>
        <span>搜索来源</span>
        <select v-model="form.webProvider" :disabled="!form.webEnabled">
          <option value="bing">必应</option>
          <option value="searxng">SearXNG</option>
        </select>
      </label>
      <label>
        <span>结果条数</span>
        <input v-model.number="form.webMaxResults" type="number" min="1" max="8" :disabled="!form.webEnabled" />
      </label>
      <label>
        <span>最多检索次数</span>
        <input v-model.number="form.webMaxRounds" type="number" min="1" max="4" :disabled="!form.webEnabled" />
      </label>
    </div>
    <!-- 必应不需要自建地址，只有选了 SearXNG 才显示。 -->
    <label v-if="form.webProvider === 'searxng'">
      <span>SearXNG 地址</span>
      <input v-model="form.webSearchUrl" type="url" spellcheck="false" placeholder="http://127.0.0.1:8888" :disabled="!form.webEnabled" />
    </label>
    <label class="check">
      <input v-model="form.webFetch" type="checkbox" :disabled="!form.webEnabled" />
      <span>允许打开网页</span>
    </label>
    <p class="hint">必应不需要密钥。如果必应不可用，把搜索来源改成你自己的 SearXNG。</p>

    <!-- 这些数值会原样传给 Ollama 的采样参数。范围和后端校验一致。 -->
    <div class="grid">
      <label>
        <span>温度 {{ Number(form.temperature).toFixed(1) }}</span>
        <input v-model.number="form.temperature" type="range" min="0" max="2" step="0.1" />
      </label>
      <label>
        <span>Top P {{ Number(form.topP).toFixed(2) }}</span>
        <input v-model.number="form.topP" type="range" min="0" max="1" step="0.05" />
      </label>
      <label>
        <span>Top K</span>
        <input v-model.number="form.topK" type="number" min="1" max="200" />
      </label>
      <label>
        <span>上下文长度</span>
        <input v-model.number="form.numCtx" type="number" min="512" max="131072" step="512" />
      </label>
      <label>
        <span>最大生成长度</span>
        <input v-model.number="form.numPredict" type="number" min="-2" max="131072" />
      </label>
      <label>
        <span>重复惩罚</span>
        <input v-model.number="form.repeatPenalty" type="number" min="0" max="2" step="0.1" />
      </label>
    </div>
    <p class="hint">最大生成长度为 -1 时不限制。上下文越大越占内存，9B 模型建议从 8192 开始。</p>

    <p v-if="error" class="banner">{{ error }}</p>
    <p v-else-if="probeMessage" class="ok">{{ probeMessage }}</p>

    <div class="settings-actions">
      <button type="button" class="primary" :disabled="saving" @click="$emit('save')">
        {{ saving ? '保存中…' : '保存配置' }}
      </button>
      <button type="button" class="ghost" :disabled="probing" @click="$emit('probe')">
        {{ probing ? '测试中…' : '测试连接' }}
      </button>
      <button type="button" class="ghost" @click="$emit('reset')">恢复默认</button>
    </div>
  </aside>
</template>
