// 知识库文档与分块的 CRUD 及原文件 URL。
import { readError, request } from './client.js'

/** 列出所有知识库文档。 */
export function listDocuments() {
  return request('/api/knowledge/documents')
}

/** 上传文件并触发后端切块入库。 */
export async function uploadDocument(file) {
  const body = new FormData()
  body.append('file', file)
  const response = await fetch('/api/knowledge/documents', { method: 'POST', body })
  if (!response.ok) {
    throw new Error(await readError(response))
  }
  return response.json()
}

/** 删除文档及其切块与向量。 */
export async function deleteDocument(id) {
  const response = await fetch(`/api/knowledge/documents/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!response.ok) {
    throw new Error(await readError(response))
  }
}

/** 保留原件，按当前规则重新分块。 */
export function rechunkDocument(id) {
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}/rechunk`, {
    method: 'POST',
    body: '{}'
  })
}

/** 构造文档原文件预览/下载 URL。 */
export function originalUrl(id, filename) {
  const name = encodeURIComponent(String(filename || 'file').replaceAll('/', '_'))
  return `/api/knowledge/documents/${encodeURIComponent(id)}/original/${name}`
}

/** 获取指定文档的全部分块列表。 */
export function listChunks(id) {
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}/chunks`)
}

/** 为文档新增一块正文。 */
export function addChunk(id, text) {
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}/chunks`, {
    method: 'POST',
    body: JSON.stringify({ text })
  })
}

/** 更新已有分块正文。 */
export function updateChunk(id, chunkId, text) {
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}/chunks/${encodeURIComponent(chunkId)}`, {
    method: 'PUT',
    body: JSON.stringify({ text })
  })
}

/** 删除单个分块。 */
export async function deleteChunk(id, chunkId) {
  const response = await fetch(
    `/api/knowledge/documents/${encodeURIComponent(id)}/chunks/${encodeURIComponent(chunkId)}`,
    { method: 'DELETE' }
  )
  if (!response.ok) {
    throw new Error(await readError(response))
  }
}