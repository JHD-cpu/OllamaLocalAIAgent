// 聚合导出各 API 模块，供应用层统一引用。
export { getDefaults, getSettings, saveSettings } from './settings.js'
export { getInfo, probeOllama, installOllama, getOllamaInstallStatus, startOllama, pullOllamaModel, deleteOllamaModel } from './ollama.js'
export { getLlmInfo, probeLlm, listLlmProviders } from './llm.js'
export { listDeps, listMissingDeps, getDepStatus, installDep } from './deps.js'
export { streamChat } from './chat.js'
export {
  listConversations,
  createConversation,
  renameConversation,
  deleteConversation,
  getConversationMessages,
  saveConversationMessages
} from './conversations.js'
export {
  addChunk,
  deleteChunk,
  deleteDocument,
  listChunks,
  listDocuments,
  originalUrl,
  rechunkDocument,
  updateChunk,
  uploadDocument
} from './knowledge.js'
export { getLogInfo } from './system.js'
export { openKnowledgeGraphWindow } from './desktop.js'