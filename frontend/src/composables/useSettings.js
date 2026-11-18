// 设置表单状态、脏检查、加载保存与 Ollama 探测。
import { computed, reactive, ref } from 'vue'
import { getDefaults, getSettings, probeOllama, saveSettings } from '../api'
import { applyWebPrompt } from '../constants/webPrompt.js'

/** 封装配置面板的表单数据与持久化流程。 */
export function useSettings() {
  const form = reactive({})
  const saved = ref({})
  const defaults = ref({})
  const loaded = ref(false)
  const loadError = ref('')
  const models = ref([])
  const settingsError = ref('')
  const probeMessage = ref('')
  const settingsOpen = ref(false)
  const saving = ref(false)
  const probing = ref(false)

  // 当前表单与上次保存快照不一致即为未保存。
  const dirty = computed(() => JSON.stringify(form) !== JSON.stringify(saved.value))

  /** 用服务端返回的配置覆盖表单与 saved 快照。 */
  function applySettings(value) {
    Object.assign(form, value)
    saved.value = { ...value }
  }

  /** 并行加载当前设置与默认值。 */
  async function load() {
    const [current, base] = await Promise.all([getSettings(), getDefaults()])
    applySettings(current)
    defaults.value = base
    loaded.value = true
  }

  /** 提交表单并更新本地快照。 */
  async function save() {
    saving.value = true
    settingsError.value = ''
    probeMessage.value = ''
    try {
      const stored = await saveSettings({ ...form })
      applySettings(stored)
      return stored
    }
    catch (error) {
      settingsError.value = error.message
      throw error
    }
    finally {
      saving.value = false
    }
  }

  /** 用当前 baseUrl 测试 Ollama 并刷新模型列表。 */
  async function probe() {
    probing.value = true
    settingsError.value = ''
    probeMessage.value = ''
    try {
      const result = await probeOllama(form.baseUrl)
      models.value = result.models || []
      if (result.ok) {
        probeMessage.value = `连接成功，Ollama ${result.version}，共 ${models.value.length} 个模型`
      }
      else {
        settingsError.value = result.message
      }
      return result
    }
    catch (error) {
      settingsError.value = error.message
      throw error
    }
    finally {
      probing.value = false
    }
  }

  /** 将表单填回默认值（需保存后生效）。 */
  function reset() {
    Object.assign(form, defaults.value)
    settingsError.value = ''
    probeMessage.value = ''
  }

  /** 根据联网开关调整系统提示词中的固定片段。 */
  function syncWebPrompt(enabled) {
    form.systemPrompt = applyWebPrompt(form.systemPrompt, enabled)
  }

  return {
    form,
    saved,
    defaults,
    loaded,
    loadError,
    models,
    settingsError,
    probeMessage,
    settingsOpen,
    saving,
    probing,
    dirty,
    applySettings,
    load,
    save,
    probe,
    reset,
    syncWebPrompt
  }
}