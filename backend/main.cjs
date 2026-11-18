const { app, BrowserWindow, dialog, Menu } = require('electron')
const path = require('path')
const { ServiceManager, resolveBackendRoot, DEFAULT_PORT } = require('./service.cjs')

let mainWindow = null
let service = null
let quitting = false

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 840,
    minWidth: 960,
    minHeight: 640,
    show: false,
    title: 'OllamaChat',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  mainWindow.once('ready-to-show', () => mainWindow.show())
  await mainWindow.loadFile(path.join(__dirname, 'loading.html'))

  mainWindow.webContents.on('context-menu', (_event, params) => {
    const contents = mainWindow.webContents
    const flags = params.editFlags || {}
    const hasSelection = Boolean(params.selectionText && params.selectionText.trim())
    const editable = Boolean(params.isEditable)
    const canCopy = hasSelection || Boolean(flags.canCopy)
    const canPaste = editable || Boolean(flags.canPaste)
    const menu = Menu.buildFromTemplate([
      {
        label: '复制',
        enabled: canCopy,
        click: () => {
          if (hasSelection || flags.canCopy) {
            contents.copy()
          }
        }
      },
      {
        label: '粘贴',
        enabled: canPaste,
        click: () => {
          contents.paste()
        }
      }
    ])
    menu.popup({ window: mainWindow })
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

function setStatus(text) {
  if (!mainWindow || mainWindow.isDestroyed()) {
    return
  }
  mainWindow.webContents.executeJavaScript(
    `window.__setStatus && window.__setStatus(${JSON.stringify(String(text))})`
  ).catch(() => {})
}

async function boot() {
  await createWindow()
  service = new ServiceManager({
    port: DEFAULT_PORT,
    backendRoot: resolveBackendRoot(app),
    onStatus: (status) => {
      if (status === 'ready') {
        setStatus('服务已就绪，正在打开页面…')
        if (mainWindow && !mainWindow.isDestroyed()) {
          const url = `http://127.0.0.1:${DEFAULT_PORT}/`
          const current = mainWindow.webContents.getURL()
          if (!current.startsWith(url)) {
            mainWindow.loadURL(url).catch((err) => console.error(err))
          }
        }
        return
      }
      if (String(status).startsWith('restart:')) {
        setStatus('服务无响应，正在重启…')
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

  try {
    setStatus('正在启动本地服务…')
    await service.start()
    setStatus('服务已就绪，正在打开页面…')
    await mainWindow.loadURL(`http://127.0.0.1:${DEFAULT_PORT}/`)
  }
  catch (err) {
    console.error(err)
    dialog.showErrorBox('OllamaChat', err.message || String(err))
    app.quit()
  }
}

app.whenReady().then(boot)

app.on('window-all-closed', () => {
  app.quit()
})

app.on('before-quit', async (event) => {
  if (quitting) {
    return
  }
  quitting = true
  event.preventDefault()
  try {
    if (service) {
      await service.stop()
    }
  }
  finally {
    app.exit(0)
  }
})
