import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import './styles/global.css'
import { notifyError } from './utils/notify.js'

const app = createApp(App)

app.config.errorHandler = (err, _instance, info) => {
  console.error('Vue error', info, err)
  notifyError(err?.message || String(err) || '界面渲染出错')
}

window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  const message = reason?.message || String(reason || '未处理的异步错误')
  console.error('Unhandled rejection', reason)
  notifyError(message)
})

window.addEventListener('error', (event) => {
  if (event.error) {
    console.error('Window error', event.error)
    notifyError(event.error.message || '脚本出错')
  }
})

app.use(ElementPlus, { locale: zhCn }).mount('#app')
