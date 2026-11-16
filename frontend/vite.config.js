import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时前端在 5173，/api 转发到本机 Spring Boot，避免浏览器跨域。
export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
