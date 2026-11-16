# 本地 Ollama 对话

用 Spring AI 调用本机 Ollama，用 Vue 提供聊天和配置界面。默认模型是 `qwen3.5:9b`。

## 启动

先确认 Ollama 已在运行：

```powershell
ollama list
```

后端：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

前端另开一个终端：

```powershell
cd frontend
npm install
npm run dev
```

浏览器打开 http://127.0.0.1:5173 。

## 可以配置的内容

页面右侧可以修改并保存：

- Ollama 地址
- 模型名称
- 系统提示词
- 思考模式
- 联网：搜索来源（必应或 SearXNG）、结果条数、检索次数，以及是否允许打开网页
- 温度、Top P、Top K
- 上下文长度、最大生成长度、重复惩罚

点「保存配置」后写入 `backend/data/settings.json`，下次启动会继续使用。`application.yml` 里的 `app.ollama` 是没有这份文件时的默认值。「测试连接」只检查地址，不会保存。
