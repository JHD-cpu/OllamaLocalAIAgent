// 应用配置的读取、默认值与保存。
import { request } from './client.js'

/** 获取当前已保存的设置。 */
export function getSettings() {
  return request('/api/settings')
}

/** 获取内置默认配置。 */
export function getDefaults() {
  return request('/api/settings/defaults')
}

/** 将设置写入后端持久化。 */
export function saveSettings(settings) {
  return request('/api/settings', { method: 'PUT', body: JSON.stringify(settings) })
}