const { app, BrowserWindow, dialog, Menu } = require('electron')
const path = require('path')
const { ServiceManager, resolveBackendRoot, DEFAULT_PORT } = require('./service.cjs')
const { EmbeddedBrowserManager } = require('./browser.cjs')
const { KnowledgeGraphWindowManager } = require('./graph-window.cjs')

let mainWindow = null
let service = null
let browsers = null
let graphWindow = null
let quitting = false

/** 为窗口注册右键菜单（复制/粘贴） */
function attachContextMenu(win) {
  // 根据选区与可编辑状态弹出复制/粘贴菜单
  win.webContents.on('context-menu', (_event, params) => {
    const contents = win.webContents
    const flags = params.editFlags || {}
    const hasSelection = Boolean(params.selectionText && params.selectionText.trim())
    const menu = Menu.buildFromTemplate([
      {
        label: '复制',
        enabled: hasSelection || Boolean(flags.canCopy),
        click: () => contents.copy()
      },
      {
        label: '粘贴',
        enabled: Boolean(params.isEditable) || Boolean(flags.canPaste),
        click: () => contents.paste()
      }
    ])
    menu.popup({ window: win })
  })
}

/** 创建主窗口并先加载 loading 页 */
async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 720,
    minHeight: 520,
    show: false,
    title: 'OllamaChat',
    backgroundColor: '#000000',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      zoomFactor: 1
    }
  })

  // 阻止 Ctrl+/- 缩放导致布局比例错乱；窗口尺寸变化只走 CSS 自适应。
  mainWindow.webContents.on('did-finish-load', () => {
    mainWindow.webContents.setZoomFactor(1)
  })
  mainWindow.webContents.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') {
      return
    }
    const key = String(input.key || '').toLowerCase()
    if ((input.control || input.meta) && (key === '=' || key === '+' || key === '-' || key === '0' || key === 'numadd' || key === 'numsub')) {
      event.preventDefault()
      mainWindow.webContents.setZoomFactor(1)
    }
  })

  mainWindow.once('ready-to-show', () => mainWindow.show())
  attachContextMenu(mainWindow)
  await mainWindow.loadFile(path.join(__dirname, 'loading.html'))

  // 主窗口关闭即退出应用（内嵌浏览器窗口不触发）
  mainWindow.on('closed', () => {
    mainWindow = null
    app.quit()
  })
}

/** 向 loading 页注入并显示状态文案 */
function setStatus(text) {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return
  }
  mainWindow.webContents.executeJavaScript(
    `window.__setStatus && window.__setStatus(${JSON.stringify(String(text))})`
  ).catch(() => {})
}

/** 在服务就绪后导航到本地 Web 应用 */
function openApp() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return
  }
  const url = `http://127.0.0.1:${DEFAULT_PORT}/`
  const current = mainWindow.webContents.getURL()
  if (!current.startsWith(url)) {
    mainWindow.loadURL(url).catch((err) => console.error(err))
  }
}

/** 启动窗口、本地 Java 服务并处理状态回调 */
async function boot() {
  await createWindow()
  service = new ServiceManager({
    port: DEFAULT_PORT,
    backendRoot: resolveBackendRoot(app),
    // 根据服务状态更新 UI 并切换 loading/应用页
    onStatus: (status) => {
      if (status === 'ready') {
        setStatus('服务已就绪，正在打开页面…')
        openApp()
        return
      }
      if (String(status).startsWith('restart:')) {
        setStatus('服务无响应，正在重启…')
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.loadFile(path.join(__dirname, 'loading.html')).catch(() => {})
        }
        return
      }
      if (String(status).startsWith('exited:')) {
        setStatus('本地服务已退出，正在重启…')
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.loadFile(path.join(__dirname, 'loading.html')).catch(() => {})
        }
        return
      }
      if (status === 'starting' || status === 'waiting') {
        setStatus(status === 'starting' ? '正在启动本地服务…' : '等待服务响应…')
      }
    }
  })

  browsers = new EmbeddedBrowserManager({
    port: DEFAULT_PORT,
    iconPath: path.join(__dirname, 'icon.png')
  })
  graphWindow = new KnowledgeGraphWindowManager({
    port: DEFAULT_PORT,
    iconPath: path.join(__dirname, 'icon.png')
  })

  try {
    setStatus('正在启动本地服务…')
    await service.start()
    browsers.start()
    graphWindow.start()
    setStatus('服务已就绪，正在打开页面…')
    openApp()
  }
  catch (err) {
    console.error(err)
    dialog.showErrorBox('OllamaChat', err.message || String(err))
    app.quit()
  }
}

// 应用就绪：隐藏菜单栏并执行启动流程
app.whenReady().then(() => {
  // 去掉窗口顶部 File/Edit 等默认菜单栏，右键复制粘贴仍保留。
  Menu.setApplicationMenu(null)
  return boot()
})

// 主窗口关闭时退出；内嵌浏览器窗口关闭不退出应用
app.on('window-all-closed', () => {
  if (!mainWindow || mainWindow.isDestroyed()) {
    app.quit()
  }
})

// 退出前先停浏览器轮询与本地 Java 服务
app.on('before-quit', async (event) => {
  if (quitting) {
    return
  }
  quitting = true
  event.preventDefault()
  try {
    if (browsers) {
      browsers.stop()
      browsers.closeAll()
    }
    if (graphWindow) {
      graphWindow.stop()
      graphWindow.close()
    }
    if (service) {
      await service.stop()
    }
  }
  finally {
    app.exit(0)
  }
})
