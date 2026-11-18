// 设置表单状态、脏检查、加载保存与 LLM 探测。
import { computed, reactive, ref } from 'vue'
import { getDefaults, getSettings, listLlmProviders, probeLlm, saveSettings } from '../api'
import { applyWebPrompt } from '../constants/webPrompt.js'

/** 封装配置面板的表单数据与持久化流程。 */
export function useSettings() {
  const form = reactive({})
  const saved = ref({})
  const defaults = ref({})
  const loaded = ref(false)
  const loadError = ref('')
  const models = ref([])
  const providers = ref([])
  const settingsError = ref('')
  const probeMessage = ref('')
  const settingsOpen = ref(false)
  const saving = ref(false)
  const probing = ref(false)

  const dirty = computed(() => JSON.stringify(form) !== JSON.stringify(saved.value))

  function applySettings(value) {
    Object.assign(form, value)
    saved.value = { ...value }
  }

  async function load() {
    const [current, base, providerList] = await Promise.all([
      getSettings(),
      getDefaults(),
      listLlmProviders().catch(() => [])
    ])
    applySettings(current)
    defaults.value = base
    providers.value = Array.isArray(providerList) ? providerList : []
    loaded.value = true
  }

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

  async function probe() {
    probing.value = true
    settingsError.value = ''
    probeMessage.value = ''
    try {
      const result = await probeLlm({
        llmProvider: form.llmProvider,
        apiKey: form.apiKey,
        baseUrl: form.baseUrl,
        model: form.model
      })
      models.value = (result.models || []).map((m) =>
        typeof m === 'string' ? { name: m } : { name: m.name || m.id || '', ...m }
      )
      if (result.ok) {
        probeMessage.value = `连接成功（${result.providerLabel || result.provider}）`
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

  function reset() {
    Object.assign(form, defaults.value)
    settingsError.value = ''
    probeMessage.value = ''
  }

  function syncWebPrompt(enabled) {
    form.systemPrompt = applyWebPrompt(form.systemPrompt, enabled)
  }

  /** 切换提供方时填入默认地址/模型（保留已填 API Key）。 */
  function applyProviderPreset(providerId) {
    const provider = providers.value.find((p) => p.id === providerId)
    form.llmProvider = providerId
    if (!provider) {
      return
    }
    if (provider.defaultBaseUrl) {
      form.baseUrl = provider.defaultBaseUrl
    }
    if (provider.defaultModel) {
      form.model = provider.defaultModel
    }
    if (!provider.requiresApiKey) {
      form.apiKey = form.apiKey || ''
    }
  }

  return {
    form,
    saved,
    defaults,
    loaded,
    loadError,
    models,
    providers,
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
    syncWebPrompt,
    applyProviderPreset
  }
}
