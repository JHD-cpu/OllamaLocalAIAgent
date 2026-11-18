// Ollama 连接状态与模型列表查询。
import { request } from './client.js'

/** 读取当前配置的 Ollama 连接信息与已安装模型。 */
export function getInfo() {
  return request('/api/ollama/info')
}

/** 用指定 baseUrl 探测 Ollama 是否可达。 */
export function probeOllama(baseUrl) {
  return request('/api/ollama/info', { method: 'POST', body: JSON.stringify({ baseUrl }) })
}