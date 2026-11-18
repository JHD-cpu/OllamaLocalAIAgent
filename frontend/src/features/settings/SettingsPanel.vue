<script setup>
// 配置弹窗（分 Tab）：模型 / 能力 / 采样。
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { applyWebPrompt } from '../../constants/webPrompt.js'

const props = defineProps({
  form: { type: Object, required: true },
  models: { type: Array, required: true },
  providers: { type: Array, default: () => [] },
  dirty: { type: Boolean, required: true },
  saving: { type: Boolean, required: true },
  probing: { type: Boolean, required: true },
  error: { type: String, default: '' },
  probeMessage: { type: String, default: '' },
  logPath: { type: String, default: '' }
})

const open = defineModel({ type: Boolean, default: false })

const emit = defineEmits(['save', 'probe', 'reset', 'models-changed', 'provider-change'])

const activeTab = ref('model')
const isOllama = computed(() => (props.form.llmProvider || 'ollama') === 'ollama')
const currentProvider = computed(() => props.providers.find((p) => p.id === props.form.llmProvider))
const selected = computed(() => props.models.find((model) => model.name === props.form.model))

function onWebEnabled(enabled) {
  props.form.systemPrompt = applyWebPrompt(props.form.systemPrompt, enabled)
}

function formatSize(size) {
  if (!size) {
    return ''
  }
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

function onReset() {
  emit('reset')
  ElMessage.info('已填回默认值，保存后才会生效')
}

function onProviderChange(id) {
  emit('provider-change', id)
}
</script>

<template>
  <el-dialog v-model="open" width="820px" align-center class="settings-dialog">
    <template #header>
      <div class="brand">
        <span class="settings-dialog-title">系统配置</span>
        <el-tag v-if="dirty" type="warning" size="small" effect="dark">未保存</el-tag>
      </div>
    </template>

    <div class="settings-form">
      <p class="field-hint">保存后才会用于下一次对话。不会自动下载依赖或模型；本机 Ollama / Python 等请自行安装，或改用远程 API。</p>
      <p v-if="logPath" class="field-hint">运行日志：{{ logPath }}（异常另有 .error 文件）</p>
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
      <el-alert v-else-if="probeMessage" type="success" :title="probeMessage" show-icon :closable="false" />

      <el-tabs v-model="activeTab" class="settings-tabs">
        <el-tab-pane label="模型" name="model">
          <div class="settings-tab-pane">
            <el-form label-width="110px" label-position="right" @submit.prevent>
              <el-form-item label="提供方">
                <el-select :model-value="form.llmProvider || 'ollama'" @change="onProviderChange">
                  <el-option
                    v-for="provider in providers"
                    :key="provider.id"
                    :label="provider.label"
                    :value="provider.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item :label="isOllama ? 'Ollama 地址' : '接口地址'">
                <el-input v-model="form.baseUrl" spellcheck="false" :placeholder="currentProvider?.defaultBaseUrl || ''" />
              </el-form-item>
              <el-form-item v-if="!isOllama" label="API Key">
                <el-input v-model="form.apiKey" type="password" show-password spellcheck="false" placeholder="远程服务密钥" />
              </el-form-item>
              <el-form-item label="模型">
                <el-select v-model="form.model" filterable allow-create default-first-option placeholder="选择或输入模型名">
                  <el-option v-for="model in models" :key="model.name" :label="model.name" :value="model.name" />
                </el-select>
              </el-form-item>
              <p v-if="selected && selected.parameterSize" class="model-meta">
                {{ selected.parameterSize }} · {{ selected.quantization }} · {{ formatSize(selected.size) }}
                <template v-if="selected.contextLength"> · 上下文上限 {{ selected.contextLength }}</template>
              </p>
              <p v-if="isOllama" class="field-hint">
                请本机自行安装并启动 Ollama（默认 http://127.0.0.1:11434）。模型用命令行 `ollama pull`。
              </p>
              <p v-else-if="form.llmProvider === 'deepseek'" class="field-hint">
                DeepSeek 默认接口 https://api.deepseek.com ，常用模型 deepseek-chat / deepseek-reasoner。
              </p>
              <p v-else-if="form.llmProvider === 'doubao'" class="field-hint">
                豆包使用火山方舟 OpenAI 兼容接口，模型名填接入点 ID（ep-xxxxxxxx）。
              </p>
              <p v-else class="field-hint">
                任意 OpenAI 兼容接口：填写 Base URL（通常含 /v1）与 API Key。
              </p>

              <el-form-item label="系统提示词">
                <el-input v-model="form.systemPrompt" type="textarea" :rows="4" placeholder="给模型的角色说明" />
              </el-form-item>
              <el-form-item label="思考模式">
                <el-switch v-model="form.think" :disabled="!isOllama" />
              </el-form-item>
              <p class="field-hint">思考模式目前主要适配本机 Ollama；远程提供方下开关无效。</p>
            </el-form>
          </div>
        </el-tab-pane>

        <el-tab-pane label="能力" name="features">
          <div class="settings-tab-pane">
            <el-form label-width="110px" label-position="right" @submit.prevent>
              <el-form-item label="允许联网">
                <el-switch v-model="form.webEnabled" @change="onWebEnabled" />
              </el-form-item>
              <p class="field-hint">开启后由模型决定是否搜索。打开网页时不会访问本机和内网地址。</p>
              <el-row :gutter="12">
                <el-col :span="8">
                  <el-form-item label="搜索来源">
                    <el-select v-model="form.webProvider" :disabled="!form.webEnabled">
                      <el-option label="必应" value="bing" />
                      <el-option label="SearXNG" value="searxng" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="结果条数">
                    <el-input-number v-model="form.webMaxResults" :min="1" :disabled="!form.webEnabled" controls-position="right" />
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="检索次数">
                    <el-input-number v-model="form.webMaxRounds" :min="1" :disabled="!form.webEnabled" controls-position="right" />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-form-item v-if="form.webProvider === 'searxng'" label="SearXNG 地址">
                <el-input v-model="form.webSearchUrl" :disabled="!form.webEnabled" spellcheck="false" />
              </el-form-item>
              <el-form-item label="打开网页">
                <el-switch v-model="form.webFetch" :disabled="!form.webEnabled" />
              </el-form-item>

              <el-divider content-position="left">知识库</el-divider>
              <el-form-item label="使用知识库">
                <el-switch v-model="form.ragEnabled" />
              </el-form-item>
              <p class="field-hint">
                本机 Ollama：嵌入模型需自行 `ollama pull`（默认 bge-m3）。远程：填写该接口支持的嵌入模型名。
              </p>
              <el-row :gutter="12">
                <el-col :span="14">
                  <el-form-item label="嵌入模型">
                    <el-select
                      v-model="form.embeddingModel"
                      filterable
                      allow-create
                      default-first-option
                      :disabled="!form.ragEnabled"
                      placeholder="选择或输入嵌入模型"
                    >
                      <el-option v-for="model in models" :key="model.name" :label="model.name" :value="model.name" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="10">
                  <el-form-item label="检索条数">
                    <el-input-number v-model="form.ragTopK" :min="1" :disabled="!form.ragEnabled" controls-position="right" />
                  </el-form-item>
                </el-col>
              </el-row>

              <el-divider content-position="left">代码</el-divider>
              <el-form-item label="允许写代码">
                <el-switch v-model="form.codeEnabled" />
              </el-form-item>
              <p class="field-hint">开启后模型可在工作区读写和运行文件。打开网站用内嵌浏览器；打开本机程序优先 open_app。</p>
              <el-form-item label="工作区">
                <el-input v-model="form.codeWorkspace" :disabled="!form.codeEnabled" spellcheck="false" />
              </el-form-item>
              <el-row :gutter="12">
                <el-col :span="12">
                  <el-form-item label="命令超时（秒）">
                    <el-input-number v-model="form.codeTimeoutSeconds" :min="1" :max="120" :disabled="!form.codeEnabled" controls-position="right" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="生成轮次">
                    <el-input-number v-model="form.codeMaxRounds" :min="1" :disabled="!form.codeEnabled" controls-position="right" />
                  </el-form-item>
                </el-col>
              </el-row>
            </el-form>
          </div>
        </el-tab-pane>

        <el-tab-pane label="采样" name="sampling">
          <div class="settings-tab-pane">
            <el-form label-width="120px" label-position="right" @submit.prevent>
              <el-form-item :label="`温度 ${Number(form.temperature).toFixed(1)}`">
                <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" />
              </el-form-item>
              <el-form-item :label="`Top P ${Number(form.topP).toFixed(2)}`">
                <el-slider v-model="form.topP" :min="0" :max="1" :step="0.05" />
              </el-form-item>
              <el-row :gutter="12">
                <el-col :span="12">
                  <el-form-item label="Top K">
                    <el-input-number v-model="form.topK" :min="1" :max="200" controls-position="right" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="上下文长度">
                    <el-input-number v-model="form.numCtx" :min="512" :max="131072" :step="512" controls-position="right" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="最大生成">
                    <el-input-number v-model="form.numPredict" :min="-2" :max="131072" controls-position="right" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="重复惩罚">
                    <el-input-number v-model="form.repeatPenalty" :min="0" :max="2" :step="0.1" :precision="1" controls-position="right" />
                  </el-form-item>
                </el-col>
              </el-row>
              <p class="field-hint">
                占用达到上下文约 80% 时，会自动把较早对话压成摘要并保留最近几轮。本机 Ollama 建议「上下文长度」≥ 32768；远程窗口由模型决定。
                Top K / 重复惩罚主要影响 Ollama。
              </p>
            </el-form>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>

    <template #footer>
      <el-button @click="onReset">恢复默认</el-button>
      <el-button :loading="probing" @click="emit('probe')">测试连接</el-button>
      <el-button type="primary" :loading="saving" @click="emit('save')">保存配置</el-button>
    </template>
  </el-dialog>
</template>
