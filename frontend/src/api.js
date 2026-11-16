// 读取失败响应。后端约定错误正文是 {"message":"..."}，解析不了就退回原文。
async function readError(response) {
  const text = await response.text()
  if (!text) {
    return `请求失败（${response.status}）`
  }
  try {
    const body = JSON.parse(text)
    return body.message || text
  }
  catch {
    return text
  }
}

// 普通 JSON 请求。失败时抛 Error，消息来自 readError。
async function request(url, options) {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    ...options
  })
  if (!response.ok) {
    throw new Error(await readError(response))
  }
  return response.json()
}

export function getSettings() {
  return request('/api/settings')
}

export function getDefaults() {
  return request('/api/settings/defaults')
}

export function saveSettings(settings) {
  return request('/api/settings', { method: 'PUT', body: JSON.stringify(settings) })
}

export function getInfo() {
  return request('/api/ollama/info')
}

// 用尚未保存的地址探测 Ollama，不改后端配置。
export function probeOllama(baseUrl) {
  return request('/api/ollama/info', { method: 'POST', body: JSON.stringify({ baseUrl }) })
}

// 读取对话的 SSE 流。signal 用来停止生成；每解析出一条 data 就调用 onEvent。
export async function streamChat(messages, { onEvent, signal }) {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ messages }),
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
    // SSE 事件之间用空行分隔。最后一段可能还不完整，留在 buffer 里等下一次读取。
    const chunks = buffer.split('\n\n')
    buffer = chunks.pop() ?? ''
    for (const chunk of chunks) {
      const data = chunk
        .split('\n')
        .filter((line) => line.startsWith('data:'))
        .map((line) => line.slice(5).trim())
        .join('\n')
      if (data) {
        onEvent(JSON.parse(data))
      }
    }
  }
}
