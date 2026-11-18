// 外部依赖仅做检测提示；已取消所有自动下载/安装。
import { ref } from 'vue'

/** 保留接口兼容，但不再弹窗、不再触发任何安装下载。 */
export function useExternalDeps() {
  const busy = ref(false)

  async function offerMissingOnStartup() {
    /* 已禁用自动下载 */
  }

  async function offerFromError() {
    return false
  }

  async function runInstall() {
    throw new Error('已取消自动下载与安装，请在本机自行安装所需依赖。')
  }

  return { busy, offerMissingOnStartup, offerFromError, runInstall }
}
