const { BaseWindow, WebContentsView, Menu, ipcMain } = require('electron')
const path = require('path')
const http = require('http')

const MAX_WINDOWS = 12
const POLL_MS = 800
const TOOLBAR_H = 44

/** 管理应用内嵌浏览器：轮询打开/读页，带地址栏工具条，支持多开。 */
class EmbeddedBrowserManager {
  /** @param {{ port: number, iconPath?: string }} options */
  constructor(options) {
    this.port = options.port
    this.iconPath = options.iconPath
    this.windows = new Set()
    this.active = null
    this.timer = null
    this.busy = false
    this.navHandlerBound = false
  }

  start() {
    this.ensureNavHandler()
    if (this.timer) {
      return
    }
    this.timer = setInterval(() => {
      this.poll().catch((err) => console.error('browser poll failed', err))
    }, POLL_MS)
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  }

  closeAll() {
    for (const win of [...this.windows]) {
      if (!win.isDestroyed()) {
        win.close()
      }
    }
    this.windows.clear()
    this.active = null
  }

  ensureNavHandler() {
    if (this.navHandlerBound) {
      return
    }
    this.navHandlerBound = true
    ipcMain.on('browser-nav', (event, action, arg) => {
      const win = [...this.windows].find((w) => w._toolbarView && w._toolbarView.webContents === event.sender)
      if (!win || win.isDestroyed()) {
        return
      }
      const contents = win._contentView?.webContents
      if (!contents || contents.isDestroyed()) {
        return
      }
      try {
        if (action === 'back') {
          if (contents.navigationHistory?.canGoBack?.()) {
            contents.navigationHistory.goBack()
          }
          else if (contents.canGoBack?.()) {
            contents.goBack()
          }
        }
        else if (action === 'forward') {
          if (contents.navigationHistory?.canGoForward?.()) {
            contents.navigationHistory.goForward()
          }
          else if (contents.canGoForward?.()) {
            contents.goForward()
          }
        }
        else if (action === 'reload') {
          contents.reload()
        }
        else if (action === 'goto' && typeof arg === 'string' && arg.trim()) {
          let url = arg.trim()
          if (!/^https?:\/\//i.test(url)) {
            url = 'https://' + url
          }
          contents.loadURL(url).catch((err) => console.error(err))
        }
      }
      catch (err) {
        console.error('browser nav failed', err)
      }
    })
  }

  async poll() {
    if (this.busy) {
      return
    }
    this.busy = true
    try {
      const payload = await this.getJson(`http://127.0.0.1:${this.port}/api/desktop/browser/pending`)
      const items = Array.isArray(payload?.items) ? payload.items : []
      for (const item of items) {
        const url = typeof item?.url === 'string' ? item.url.trim() : ''
        if (url) {
          this.open(url)
        }
      }
      const reads = Array.isArray(payload?.reads) ? payload.reads : []
      for (const read of reads) {
        const id = typeof read?.id === 'string' ? read.id : ''
        if (id) {
          await this.fulfillRead(id)
        }
      }
    }
    catch (err) {
      // 服务尚未就绪时忽略，避免刷屏
      if (!String(err.message || err).includes('ECONNREFUSED')) {
        console.error('browser poll failed', err)
      }
    }
    finally {
      this.busy = false
    }
  }

  contentOf(win) {
    return win?._contentView?.webContents || null
  }

  async fulfillRead(id) {
    let text = ''
    const win = this.active && !this.active.isDestroyed()
      ? this.active
      : [...this.windows].reverse().find((w) => !w.isDestroyed())
    const contents = this.contentOf(win)
    if (contents && !contents.isDestroyed()) {
      try {
        text = await contents.executeJavaScript(
          `(() => {
            const body = document.body ? document.body.innerText : ''
            const title = document.title || ''
            return (title ? title + '\\n\\n' : '') + (body || '')
          })()`,
          true
        )
      }
      catch (err) {
        console.error('read page failed', err)
      }
    }
    await this.postJson(`http://127.0.0.1:${this.port}/api/desktop/browser/read-result`, {
      id,
      text: typeof text === 'string' ? text.slice(0, 200000) : ''
    })
  }

  open(url) {
    this.prune()
    if (this.windows.size >= MAX_WINDOWS) {
      console.warn(`embedded browser limit ${MAX_WINDOWS}, skip ${url}`)
      return null
    }

    // Electron 30+：用 BaseWindow + WebContentsView，避免废弃 BrowserView 导致空白/不显示
    const win = new BaseWindow({
      width: 1100,
      height: 800,
      minWidth: 640,
      minHeight: 480,
      show: false,
      title: '浏览器',
      icon: this.iconPath,
      autoHideMenuBar: true,
      backgroundColor: '#0c1626'
    })

    const toolbar = new WebContentsView({
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        preload: path.join(__dirname, 'browser-preload.cjs')
      }
    })
    const content = new WebContentsView({
      webPreferences: {
        contextIsolation: true,
        nodeIntegration: false,
        sandbox: true,
        partition: `persist:embedded-browser-${Date.now()}`
      }
    })

    win._toolbarView = toolbar
    win._contentView = content
    win._embeddedBrowserManager = this

    win.contentView.addChildView(toolbar)
    win.contentView.addChildView(content)

    const layout = () => {
      if (win.isDestroyed()) {
        return
      }
      const { width, height } = win.getContentBounds()
      toolbar.setBounds({ x: 0, y: 0, width, height: TOOLBAR_H })
      content.setBounds({ x: 0, y: TOOLBAR_H, width, height: Math.max(0, height - TOOLBAR_H) })
    }
    layout()
    win.on('resize', layout)

    const syncUrl = () => {
      try {
        const current = content.webContents.getURL()
        if (!toolbar.webContents.isDestroyed()) {
          toolbar.webContents.send('browser-url', current)
        }
      }
      catch {
        /* ignore */
      }
    }

    content.webContents.on('did-navigate', syncUrl)
    content.webContents.on('did-navigate-in-page', syncUrl)
    content.webContents.on('page-title-updated', (_event, title) => {
      if (title && !win.isDestroyed()) {
        win.setTitle(title)
      }
    })
    content.webContents.setWindowOpenHandler(({ url: next }) => {
      if (typeof next === 'string' && /^https?:\/\//i.test(next)) {
        this.open(next)
      }
      return { action: 'deny' }
    })
    attachBrowserContextMenu(win, content.webContents)

    toolbar.webContents.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(toolbarHtml())).catch((err) => {
      console.error('load toolbar failed', err)
    })
    content.webContents.loadURL(url).catch((err) => {
      console.error('load browser url failed', url, err)
    })

    this.windows.add(win)
    this.active = win
    win.on('focus', () => {
      this.active = win
    })
    // 不要只靠 ready-to-show：内容在子 View 里加载时，父窗口可能永不触发该事件
    win.show()
    try {
      win.focus()
    }
    catch {
      /* ignore */
    }
    win.on('closed', () => {
      this.windows.delete(win)
      if (this.active === win) {
        this.active = null
      }
    })
    return win
  }

  prune() {
    for (const win of [...this.windows]) {
      if (win.isDestroyed()) {
        this.windows.delete(win)
      }
    }
  }

  getJson(url) {
    return new Promise((resolve, reject) => {
      const req = http.get(url, { timeout: 2000 }, (res) => {
        let raw = ''
        res.setEncoding('utf8')
        res.on('data', (chunk) => {
          raw += chunk
        })
        res.on('end', () => {
          if (res.statusCode && res.statusCode >= 400) {
            reject(new Error(`HTTP ${res.statusCode}`))
            return
          }
          try {
            resolve(JSON.parse(raw || '{}'))
          }
          catch (err) {
            reject(err)
          }
        })
      })
      req.on('error', reject)
      req.on('timeout', () => {
        req.destroy()
        reject(new Error('timeout'))
      })
    })
  }

  postJson(url, body) {
    return new Promise((resolve, reject) => {
      const data = Buffer.from(JSON.stringify(body || {}), 'utf8')
      const target = new URL(url)
      const req = http.request({
        hostname: target.hostname,
        port: target.port,
        path: target.pathname,
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': data.length
        },
        timeout: 3000
      }, (res) => {
        res.on('data', () => {})
        res.on('end', () => resolve())
      })
      req.on('error', reject)
      req.on('timeout', () => {
        req.destroy()
        reject(new Error('timeout'))
      })
      req.write(data)
      req.end()
    })
  }
}

function toolbarHtml() {
  return `<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
html,body{margin:0;height:100%;font-family:"Segoe UI",sans-serif;background:#0c1626;color:#d7e6f5}
#bar{display:flex;gap:6px;align-items:center;padding:6px 8px;height:100%;box-sizing:border-box;border-bottom:1px solid rgba(64,196,210,.28)}
button{padding:4px 8px;border:1px solid rgba(90,140,170,.35);border-radius:4px;background:rgba(255,255,255,.04);color:#d7e6f5;cursor:pointer}
button:hover{border-color:#2fd6c9;color:#2fd6c9}
input{flex:1;min-width:0;padding:6px 8px;border:1px solid rgba(90,140,170,.35);border-radius:4px;background:rgba(4,10,20,.75);color:#d7e6f5;outline:none}
input:focus{border-color:#2fd6c9}
</style></head><body><div id="bar">
<button id="back" type="button">←</button>
<button id="forward" type="button">→</button>
<button id="reload" type="button">↻</button>
<input id="url" spellcheck="false" placeholder="输入网址，回车跳转"/>
<button id="go" type="button">前往</button>
</div>
<script>
const api = window.browserChrome
const urlInput = document.getElementById('url')
document.getElementById('back').onclick = () => api.back()
document.getElementById('forward').onclick = () => api.forward()
document.getElementById('reload').onclick = () => api.reload()
function go(){ let u=urlInput.value.trim(); if(!u)return; api.goto(u) }
document.getElementById('go').onclick = go
urlInput.addEventListener('keydown', e => { if (e.key === 'Enter') go() })
api.onUrl(u => { urlInput.value = u || '' })
</script></body></html>`
}

function attachBrowserContextMenu(win, contents) {
  contents.on('context-menu', (_event, params) => {
    const flags = params.editFlags || {}
    const hasSelection = Boolean(params.selectionText && params.selectionText.trim())
    const linkUrl = typeof params.linkURL === 'string' ? params.linkURL : ''
    const template = [
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
    ]
    if (/^https?:\/\//i.test(linkUrl)) {
      template.push({ type: 'separator' })
      template.push({
        label: '在新窗口打开链接',
        click: () => {
          const manager = win._embeddedBrowserManager
          if (manager) {
            manager.open(linkUrl)
          }
        }
      })
    }
    Menu.buildFromTemplate(template).popup({ window: win })
  })
}

module.exports = { EmbeddedBrowserManager }
