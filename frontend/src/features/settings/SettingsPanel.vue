<script setup>
// 配置弹窗：Ollama、联网、RAG、代码工作区与采样参数表单。
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { applyWebPrompt } from '../../constants/webPrompt.js'

const props = defineProps({
  form: { type: Object, required: true },
  models: { type: Array, required: true },
  dirty: { type: Boolean, required: true },
  saving: { type: Boolean, required: true },
  probing: { type: Boolean, required: true },
  error: { type: String, default: '' },
  probeMessage: { type: String, default: '' }
})

const open = defineModel({ type: Boolean, default: false })

const emit = defineEmits(['save', 'probe', 'reset'])

/** 联网开关变化时同步系统提示词中的联网说明。 */
function onWebEnabled(enabled) {
  props.form.systemPrompt = applyWebPrompt(props.form.systemPrompt, enabled)
}

// 当前输入的模型名如果在已安装列表里，就显示它的参数量和体积。
const selected = computed(() => props.models.find((model) => model.name === props.form.model))

// 后端给的是字节，界面上显示成 GB。
function formatSize(size) {
  if (!size) {
    return ''
  }
  return `${(size / 1024 / 1024 / 1024).toFixed(1)} GB`
}

/** 恢复默认并提示用户需保存才生效。 */
function onReset() {
  emit('reset')
  ElMessage.info('已填回默认值，保存后才会生效')
}
</script>

<template>
  <el-dialog v-model="open" title="配置" width="760px" align-center>
    <template #header>
      <div class="brand">
        <span>配置</span>
        <el-tag v-if="dirty" type="warning" size="small">未保存</el-tag>
      </div>
    </template>

    <div class="settings-form">
      <p class="field-hint">保存后才会用于下一次对话。配置写在后端的 data/settings.json。</p>
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
      <el-alert v-else-if="probeMessage" type="success" :title="probeMessage" show-icon :closable="false" />

      <el-form label-width="110px" label-position="right" @submit.prevent>
        <el-divider content-position="left">连接</el-divider>
        <el-form-item label="Ollama 地址">
          <el-input v-model="form.baseUrl" spellcheck="false" />
        </el-form-item>
        <el-form-item label="模型">
          <!-- 可以选已安装的模型，也可以手写一个还没出现在列表里的名称。 -->
          <el-select v-model="form.model" filterable allow-create default-first-option placeholder="选择或输入模型">
            <el-option v-for="model in models" :key="model.name" :label="model.name" :value="model.name" />
          </el-select>
        </el-form-item>
        <p v-if="selected" class="model-meta">
          {{ selected.parameterSize }} · {{ selected.quantization }} · {{ formatSize(selected.size) }}
          <template v-if="selected.contextLength"> · 上下文上限 {{ selected.contextLength }}</template>
        </p>

        <el-divider content-position="left">角色</el-divider>
        <el-form-item label="系统提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4" placeholder="给模型的角色说明" />
        </el-form-item>
        <el-form-item label="思考模式">
          <el-switch v-model="form.think" />
        </el-form-item>
        <p class="field-hint">适合推理题。开启后会先输出思考过程，速度更慢。</p>

        <el-divider content-position="left">联网</el-divider>
        <el-form-item label="允许联网">
          <el-switch v-model="form.webEnabled" @change="onWebEnabled" />
        </el-form-item>
        <p class="field-hint">开启后由模型自己决定要不要搜索：只有实时信息、知识截止之后的事和不确定的事实才会搜。搜索前会把问题改写成更具体的关键词。打开网页时不会访问本机和内网地址。</p>
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
        <!-- 必应不需要自建地址，只有选了 SearXNG 才显示。 -->
        <el-form-item v-if="form.webProvider === 'searxng'" label="SearXNG 地址">
          <el-input v-model="form.webSearchUrl" :disabled="!form.webEnabled" spellcheck="false" />
        </el-form-item>
        <el-form-item label="打开网页">
          <el-switch v-model="form.webFetch" :disabled="!form.webEnabled" />
        </el-form-item>
        <p class="field-hint">必应不需要密钥。如果必应不可用，把搜索来源改成你自己的 SearXNG。</p>

        <el-divider content-position="left">知识库</el-divider>
        <el-form-item label="使用知识库">
          <el-switch v-model="form.ragEnabled" />
        </el-form-item>
        <p class="field-hint">开启后，由模型自己决定要不要检索知识库。新闻等实时问题会走联网，不会每次都查资料。嵌入模型需要先在 Ollama 里拉取，默认是 bge-m3。</p>
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

        <el-divider content-position="left">代码生成</el-divider>
        <el-form-item label="允许写代码">
          <el-switch v-model="form.codeEnabled" />
        </el-form-item>
        <p class="field-hint">开启后模型可以在工作区里读写和运行文件，也可以用 cmd 打开程序，例如 start chrome https://www.baidu.com。</p>
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

        <el-divider content-position="left">采样</el-divider>
        <!-- 这些数值会原样传给 Ollama 的采样参数。范围和后端校验一致。 -->
        <el-form-item :label="`温度 ${Number(form.temperature).toFixed(1)}`">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" />
        </el-form-item>
        <p class="field-hint">温度控制回答的随机程度。越低越稳、越偏「挑概率最高的词」；越高越发散、更有创意也更容易胡说。日常问答常用 0.6～0.8，要严谨可降到 0.2～0.4。</p>
        <el-form-item :label="`Top P ${Number(form.topP).toFixed(2)}`">
          <el-slider v-model="form.topP" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <p class="field-hint">Top P（核采样）只从「累计概率达到 P」的那一截候选词里抽。例如 0.9 表示忽略很靠后的冷门词。数值越小选词越保守；一般与温度一起调，默认附近的 0.9 较稳妥。</p>
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
        <p class="field-hint">Top K 表示每次只从概率最高的 K 个词里选。K 越小越死板，K 越大可选词越多。它和 Top P 都会限制候选范围，通常保留默认即可。最大生成长度为 -1 时不限制；上下文越大越占内存，9B 模型建议从 8192 开始。</p>
      </el-form>
    </div>

    <template #footer>
      <el-button @click="onReset">恢复默认</el-button>
      <el-button :loading="probing" @click="emit('probe')">测试连接</el-button>
      <el-button type="primary" :loading="saving" @click="emit('save')">保存配置</el-button>
    </template>
  </el-dialog>
</template>
