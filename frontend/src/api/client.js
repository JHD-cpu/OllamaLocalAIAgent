// 封装 fetch 的 JSON 请求与错误信息解析。

/** 从非成功响应体中提取可读错误文案。 */
export async function readError(response) {
  const text = await response.text()
  if (!text) {
    return `请求失败（${response.status}）`
  }
  try {
    const body = JSON.parse(text)
    if (body.message) {
      return body.message
    }
    if (body.error && body.path) {
      return `${body.error}（${body.path}）`
    }
    if (body.error) {
      return String(body.error)
    }
    return text
  }
  catch {
    return text
  }
}

/** 发起 JSON API 请求，失败时抛出带 message 的 Error；204/空体返回 null。 */
export async function request(url, options) {
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    ...options
  })
  if (!response.ok) {
    throw new Error(await readError(response))
  }
  if (response.status === 204) {
    return null
  }
  const text = await response.text()
  if (!text) {
    return null
  }
  return JSON.parse(text)
}
