// 本机外部依赖检测与自动安装。
import { request } from './client.js'

/** 列出全部依赖状态。 */
export function listDeps() {
  return request('/api/deps')
}

/** 列出尚未安装且可自动安装的依赖。 */
export function listMissingDeps() {
  return request('/api/deps/missing')
}

/** 查询单个依赖状态。 */
export function getDepStatus(id) {
  return request(`/api/deps/${encodeURIComponent(id)}`)
}

/** 开始自动安装指定依赖。 */
export function installDep(id) {
  return request(`/api/deps/${encodeURIComponent(id)}/install`, { method: 'POST', body: '{}' })
}
