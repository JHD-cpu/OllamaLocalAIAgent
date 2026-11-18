// 聚合导出各 API 模块，供应用层统一引用。
export { getDefaults, getSettings, saveSettings } from './settings.js'
export { getInfo, probeOllama } from './ollama.js'
export { streamChat } from './chat.js'
export {
  addChunk,
  deleteChunk,
  deleteDocument,
  listChunks,
  listDocuments,
  originalUrl,
  updateChunk,
  uploadDocument
} from './knowledge.js'