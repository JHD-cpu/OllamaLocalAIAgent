// 多提供方 LLM 状态与探测。
import { request } from './client.js'

/** 读取当前保存配置的 LLM 连接信息。 */
export function getLlmInfo() {
  return request('/api/llm/info')
}

/** 用表单中的临时配置探测连通性。 */
export function probeLlm(payload) {
  return request('/api/llm/info', { method: 'POST', body: JSON.stringify(payload) })
}

/** 可选提供方列表。 */
export function listLlmProviders() {
  return request('/api/llm/providers')
}
