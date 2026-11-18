import { request } from './client.js'

/** 查询服务端日志文件路径。 */
export function getLogInfo() {
  return request('/api/system/log')
}
