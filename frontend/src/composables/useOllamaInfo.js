// 维护当前 LLM 提供方连接状态（本机 Ollama 或远程）；不再引导自动安装 Ollama/模型。
import { computed, ref } from 'vue'
import { getLlmInfo } from '../api'

/** 维护连接状态文案，并提供 refreshInfo。 */
export function useOllamaInfo(modelsRef) {
  const info = ref({
    ok: false,
    version: '',
    message: '正在检查模型服务…',
    models: [],
    provider: 'ollama',
    providerLabel: '本机 Ollama',
    remote: false
  })

  const statusText = computed(() => {
    if (info.value.ok) {
      const label = info.value.providerLabel || info.value.provider || '模型'
      return `${label} · ${info.value.version || '已连接'}`
    }
    return info.value.message || '未连接'
  })

  function applyInfo(result) {
    info.value = {
      ok: !!result?.ok,
      version: result?.version || '',
      message: result?.message || '',
      models: result?.models || [],
      provider: result?.provider || 'ollama',
      providerLabel: result?.providerLabel || '',
      remote: !!result?.remote,
      baseUrl: result?.baseUrl || '',
      model: result?.model || ''
    }
    if (modelsRef) {
      modelsRef.value = (result?.models || []).map((m) =>
        typeof m === 'string' ? { name: m } : { name: m.name || m.id || '', ...m }
      )
    }
    return info.value
  }

  async function refreshInfo() {
    return applyInfo(await getLlmInfo())
  }

  return { info, statusText, refreshInfo }
}
