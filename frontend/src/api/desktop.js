import { request } from './client.js'

/** 请求 Electron 打开三维知识图谱桌面窗口。 */
export function openKnowledgeGraphWindow() {
  return request('/api/desktop/graph/open', { method: 'POST', body: '{}' })
}
