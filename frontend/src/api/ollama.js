// Ollama 连接状态、自动安装、启动与模型拉取/删除。
import { readError, request } from './client.js'

/** 读取当前配置的 Ollama 连接信息与已安装模型。 */
export function getInfo() {
  return request('/api/ollama/info')
}

/** 用指定 baseUrl 探测 Ollama 是否可达。 */
export function probeOllama(baseUrl) {
  return request('/api/ollama/info', { method: 'POST', body: JSON.stringify({ baseUrl }) })
}

/** 开始自动安装本机 Ollama（Windows）。 */
export function installOllama() {
  return request('/api/ollama/install', { method: 'POST', body: '{}' })
}

/** 查询自动安装进度。 */
export function getOllamaInstallStatus() {
  return request('/api/ollama/install/status')
}

/** 启动已安装但未运行的 Ollama。 */
export function startOllama() {
  return request('/api/ollama/start', { method: 'POST', body: '{}' })
}

/** 流式拉取模型，onEvent 接收 {type,text}。 */
export async function pullOllamaModel(name, { onEvent, signal } = {}) {
  const response = await fetch('/api/ollama/pull', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ name }),
    signal
  })
  if (!response.ok) {
    throw new Error(await readError(response))
  }
  if (!response.body) {
    throw new Error('浏览器不支持流式响应')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    const chunks = buffer.split('\n\n')
    buffer = chunks.pop() ?? ''
    for (const chunk of chunks) {
      const data = chunk
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('\n')
      if (data && onEvent) {
        onEvent(JSON.parse(data))
      }
    }
  }
}

/** 删除本地模型。 */
export function deleteOllamaModel(name) {
  return request('/api/ollama/models', {
    method: 'DELETE',
    body: JSON.stringify({ name })
  })
}
