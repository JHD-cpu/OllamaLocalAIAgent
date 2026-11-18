// 多会话历史 API。
import { request } from './client.js'

/** 列出会话（按更新时间倒序）。 */
export function listConversations() {
  return request('/api/conversations')
}

/** 新建会话。 */
export function createConversation(title) {
  return request('/api/conversations', {
    method: 'POST',
    body: JSON.stringify({ title: title || '新对话' })
  })
}

/** 重命名会话。 */
export function renameConversation(id, title) {
  return request(`/api/conversations/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ title })
  })
}

/** 删除会话。 */
export function deleteConversation(id) {
  return request(`/api/conversations/${id}`, { method: 'DELETE' })
}

/** 读取会话消息。 */
export function getConversationMessages(id) {
  return request(`/api/conversations/${id}/messages`)
}

/** 覆盖保存会话消息。 */
export function saveConversationMessages(id, messages) {
  return request(`/api/conversations/${id}/messages`, {
    method: 'PUT',
    body: JSON.stringify({ messages })
  })
}
