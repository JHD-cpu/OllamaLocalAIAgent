// 轮询式展示 Ollama 连接状态并将模型列表写入外部 ref。
import { computed, ref } from 'vue'
import { getInfo } from '../api'

/** 维护 Ollama 健康状态文案，并提供 refreshInfo 拉取最新信息。 */
export function useOllamaInfo(modelsRef) {
  const info = ref({ ok: false, version: '', message: '正在检查 Ollama…', models: [] })

  // 连接成功时显示版本，否则显示错误或「未连接」。
  const statusText = computed(() => {
    if (info.value.ok) {
      return `已连接 ${info.value.version}`
    }
    return info.value.message || '未连接'
  })

  /** 请求后端 Ollama 信息并可选同步模型列表。 */
  async function refreshInfo() {
    const result = await getInfo()
    info.value = result
    if (modelsRef) {
      modelsRef.value = result.models || []
    }
    return result
  }

  return { info, statusText, refreshInfo }
}