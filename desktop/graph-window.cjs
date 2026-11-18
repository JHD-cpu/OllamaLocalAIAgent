const { BrowserWindow, Menu } = require('electron')
const path = require('path')

/**
 * 三维知识图谱独立桌面窗口：轮询后端队列后打开/前置窗口。
 */
class KnowledgeGraphWindowManager {
  constructor(options) {
    this.port = options.port || 8080
    this.iconPath = options.iconPath
    this.window = null
    this.timer = null
    this.stopped = false
  }

  start() {
    this.stopped = false
    if (this.timer) {
      clearInterval(this.timer)
    }
    this.timer = setInterval(() => {
      this.poll().catch((err) => console.error('graph window poll failed', err))
    }, 800)
  }

  stop() {
    this.stopped = true
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  }

  close() {
    if (this.window && !this.window.isDestroyed()) {
      this.window.close()
    }
    this.window = null
  }

  async poll() {
    if (this.stopped) {
      return
    }
    const payload = await this.getJson(`http://127.0.0.1:${this.port}/api/desktop/graph/pending`)
    if (payload && payload.open) {
      this.openOrFocus()
    }
  }

  openOrFocus() {
    if (this.window && !this.window.isDestroyed()) {
      if (this.window.isMinimized()) {
        this.window.restore()
      }
      this.window.show()
      this.window.focus()
      return
    }
    this.window = new BrowserWindow({
      width: 1120,
      height: 760,
      minWidth: 720,
      minHeight: 480,
      title: '三维知识点树',
      backgroundColor: '#000000',
      icon: this.iconPath,
      autoHideMenuBar: true,
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        zoomFactor: 1
      }
    })
    this.window.webContents.on('did-finish-load', () => {
      if (this.window && !this.window.isDestroyed()) {
        this.window.webContents.setZoomFactor(1)
      }
    })
    this.window.setMenuBarVisibility(false)
    this.window.on('closed', () => {
      this.window = null
    })
    this.window.webContents.on('context-menu', (_event, params) => {
      const contents = this.window.webContents
      const flags = params.editFlags || {}
      const hasSelection = Boolean(params.selectionText && params.selectionText.trim())
      const menu = Menu.buildFromTemplate([
        {
          label: '复制',
          enabled: hasSelection || Boolean(flags.canCopy),
          click: () => contents.copy()
        }
      ])
      menu.popup({ window: this.window })
    })
    const url = `http://127.0.0.1:${this.port}/?view=knowledge-graph`
    this.window.loadURL(url).catch((err) => console.error('load knowledge graph failed', err))
  }

  getJson(url) {
    return new Promise((resolve, reject) => {
      const http = require('http')
      const req = http.get(url, (res) => {
        let raw = ''
        res.setEncoding('utf8')
        res.on('data', (chunk) => {
          raw += chunk
        })
        res.on('end', () => {
          try {
            resolve(JSON.parse(raw || '{}'))
          }
          catch (err) {
            reject(err)
          }
        })
      })
      req.on('error', reject)
      req.setTimeout(3000, () => {
        req.destroy(new Error('timeout'))
      })
    })
  }
}

module.exports = { KnowledgeGraphWindowManager }
