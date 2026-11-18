const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('browserChrome', {
  back: () => ipcRenderer.send('browser-nav', 'back'),
  forward: () => ipcRenderer.send('browser-nav', 'forward'),
  reload: () => ipcRenderer.send('browser-nav', 'reload'),
  goto: (url) => ipcRenderer.send('browser-nav', 'goto', url),
  onUrl: (cb) => {
    ipcRenderer.on('browser-url', (_event, url) => {
      if (typeof cb === 'function') {
        cb(url)
      }
    })
  }
})
