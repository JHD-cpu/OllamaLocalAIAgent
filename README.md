# OllamaChat（阿喵）

本地 AI 桌面助手：对话 + 知识库 RAG + 联网 + 本机工具（开网页/程序/截屏/工作区脚本）+ 三维知识点图谱。

技术栈：Electron 壳 · Spring Boot / Spring AI 后端 · Vue 3 前端 · Ollama / OpenAI 兼容模型。

## 开发启动

先确认本机 Ollama 可用：

```powershell
ollama list
```

后端：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

前端另开终端：

```powershell
cd frontend
npm install
npm run dev
```

浏览器打开 http://127.0.0.1:5173 。

## 打包 Windows 安装包

仓库根目录：

```powershell
.\package-windows.ps1
```

产物在 `dist/OllamaChat-Setup-*.exe`。安装后数据目录为 `%APPDATA%\OllamaChat`。

打包流程会先跑后端单元测试，再打 jar 与 NSIS 安装包。

## 主要能力

| 能力 | 说明 |
|---|---|
| 多模型 | Ollama / DeepSeek / 豆包 / OpenAI 兼容 |
| 知识库 | pdf/txt/md/docx/xlsx 入库、主题树分块、向量检索、3D 图谱 |
| 联网 | 必应或 SearXNG 搜索，可选抓取页面 |
| 本机工具 | 内嵌浏览器、打开程序、截屏（视觉描述）、记忆 |
| 代码工具 | 工作区内读写文件；`run_file` 仅脚本；`run_cmd` 白名单诊断命令 |

## 配置

页面设置可改模型、采样、联网、知识库检索条数（1–12）、代码工作区等。保存后写入 `data/settings.json`（安装态在 `%APPDATA%\OllamaChat\data\`）。

`application.yml` 的 `app.ollama` 是无本地配置文件时的默认值。

## 安全说明

- 服务只监听 `127.0.0.1`，无登录鉴权，勿对公网暴露。
- `run_cmd` 有危险模式拦截与命令白名单；破坏性操作请人工执行。
- 启动时仅尝试结束本应用相关的 Java 占用端口进程。

## 版本

当前版本见 `desktop/package.json` / 安装包文件名。发布记录在 GitHub / Gitee Releases。
