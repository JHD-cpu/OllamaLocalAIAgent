// 统一界面错误/警告/成功提示，并保证用户能看到异常信息。
import { ElMessage } from 'element-plus'

let lastError = ''
let lastAt = 0

/** 错误气泡；短时间相同文案去重。 */
export function notifyError(message, options = {}) {
  const text = String(message || '出错了').trim() || '出错了'
  const now = Date.now()
  if (text === lastError && now - lastAt < 1500) {
    return
  }
  lastError = text
  lastAt = now
  ElMessage({
    type: 'error',
    message: text,
    duration: 7000,
    showClose: true,
    grouping: true,
    ...options
  })
}

export function notifyWarn(message, options = {}) {
  ElMessage({
    type: 'warning',
    message: String(message || '').trim() || '请注意',
    duration: 5000,
    showClose: true,
    grouping: true,
    ...options
  })
}

export function notifySuccess(message, options = {}) {
  ElMessage({
    type: 'success',
    message: String(message || '').trim() || '完成',
    duration: 2500,
    showClose: true,
    grouping: true,
    ...options
  })
}
