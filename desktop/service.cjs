const { spawn } = require('child_process')
const fs = require('fs')
const http = require('http')
const path = require('path')

const DEFAULT_PORT = 8080
const HEARTBEAT_MS = 2000
const FAIL_LIMIT_MS = 10_000
const START_WAIT_MS = 60_000

class ServiceManager {
  /** 初始化端口、后端路径与状态回调 */
  constructor(options) {
    this.port = options.port || DEFAULT_PORT
    this.backendRoot = options.backendRoot
    this.onStatus = options.onStatus || (() => {}) // 无回调时的空实现
    this.child = null
    this.stopping = false
    this.restarting = false
    this.failSince = 0
    this.timer = null
    this.baseUrl = `http://127.0.0.1:${this.port}`
  }

  /** 释放端口、启动 Java 进程并等待就绪 */
  async start() {
    this.stopping = false
    await this.ensurePortFree()
    this.spawnJava()
    this.startWatch()
    await this.waitUntilReady(START_WAIT_MS)
  }

  /** 停止心跳轮询并终止子进程 */
  async stop() {
    this.stopping = true
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
    await this.killChild()
  }

  /** 用 runtime 中的 java 启动 ollama-chat.jar */
  spawnJava() {
    const java = path.join(this.backendRoot, 'runtime', 'bin', 'java.exe')
    const jar = path.join(this.backendRoot, 'app', 'ollama-chat.jar')
    if (!fs.existsSync(java)) {
      throw new Error(`找不到 Java 运行时：${java}`)
    }
    if (!fs.existsSync(jar)) {
      throw new Error(`找不到服务 jar：${jar}`)
    }
    this.onStatus('starting')
    this.child = spawn(java, [
      '-Dfile.encoding=UTF-8',
      '-Dapp.installed=true',
      '-Dapp.desktop=true',
      '-Dapp.open-browser=false',
      '-jar',
      jar,
      `--server.port=${this.port}`,
      '--server.address=127.0.0.1'
    ], {
      cwd: this.backendRoot,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    })
    this.child.stdout.on('data', (chunk) => this.log('out', chunk))
    this.child.stderr.on('data', (chunk) => this.log('err', chunk))
    // 子进程退出时通知并记录失败起点
    this.child.on('exit', (code, signal) => {
      this.child = null
      this.onStatus(`exited:${code ?? signal ?? '?'}`)
      if (!this.stopping && !this.restarting) {
        this.failSince = this.failSince || Date.now()
      }
    })
  }

  /** 将 Java 子进程标准输出/错误打印到控制台 */
  log(kind, chunk) {
    const text = chunk.toString('utf8').trim()
    if (text) {
      console.log(`[java:${kind}] ${text}`)
    }
  }

  /** 启动定时心跳检测 */
  startWatch() {
    if (this.timer) {
      clearInterval(this.timer)
    }
    // 周期性执行心跳 tick
    this.timer = setInterval(() => {
      this.tick().catch((err) => console.error('watch tick failed', err))
    }, HEARTBEAT_MS)
  }

  /** 单次心跳：成功则标记就绪，超时则触发重启 */
  async tick() {
    if (this.stopping || this.restarting) {
      return
    }
    try {
      await this.request('POST', '/api/desktop/heartbeat')
      this.failSince = 0
      this.onStatus('ready')
    }
    catch {
      if (!this.failSince) {
        this.failSince = Date.now()
        this.onStatus('waiting')
      }
      if (Date.now() - this.failSince >= FAIL_LIMIT_MS) {
        await this.restart('服务超过 10 秒无响应')
      }
    }
  }

  /** 杀死旧进程后重新拉起 Java 服务 */
  async restart(reason) {
    if (this.stopping || this.restarting) {
      return
    }
    this.restarting = true
    this.onStatus(`restart:${reason}`)
    console.warn(reason)
    try {
      await this.killChild()
      await sleep(500)
      await this.ensurePortFree()
      this.failSince = 0
      this.spawnJava()
      await this.waitUntilReady(START_WAIT_MS)
      this.onStatus('ready')
    }
    finally {
      this.restarting = false
    }
  }

  /** 轮询健康与心跳接口直至超时或停止 */
  async waitUntilReady(timeoutMs) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (this.stopping) {
        throw new Error('已停止')
      }
      try {
        await this.request('GET', '/api/desktop/health')
        await this.request('POST', '/api/desktop/heartbeat')
        this.failSince = 0
        this.onStatus('ready')
        return
      }
      catch {
        await sleep(400)
      }
    }
    throw new Error(`服务在 ${timeoutMs / 1000} 秒内没有就绪`)
  }

  /** 向本地后端发起 HTTP 请求 */
  request(method, pathname) {
    return new Promise((resolve, reject) => {
      const req = http.request(`${this.baseUrl}${pathname}`, {
        method,
        timeout: 3000,
        headers: { Accept: 'application/json' }
      }, (res) => {
        let body = ''
        res.setEncoding('utf8')
        res.on('data', (chunk) => { body += chunk })
        res.on('end', () => {
          if (res.statusCode && res.statusCode >= 200 && res.statusCode < 300) {
            resolve(body)
          }
          else {
            reject(new Error(`HTTP ${res.statusCode}`))
          }
        })
      })
      req.on('timeout', () => {
        req.destroy()
        reject(new Error('timeout'))
      })
      req.on('error', reject)
      req.end()
    })
  }

  /** 使用 taskkill 强制结束 Java 子进程树 */
  async killChild() {
    if (!this.child || !this.child.pid) {
      return
    }
    const pid = this.child.pid
    await new Promise((resolve) => {
      const killer = spawn('taskkill', ['/PID', String(pid), '/T', '/F'], {
        windowsHide: true,
        stdio: 'ignore'
      })
      killer.on('exit', () => resolve())
      killer.on('error', () => resolve())
      setTimeout(resolve, 3000)
    })
    this.child = null
  }

  /** 若端口被占用则杀进程并等待释放 */
  async ensurePortFree() {
    if (!(await portInUse(this.port))) {
      return
    }
    await killPort(this.port)
    const deadline = Date.now() + 5000
    while (Date.now() < deadline) {
      if (!(await portInUse(this.port))) {
        return
      }
      await sleep(200)
    }
    throw new Error(`端口 ${this.port} 仍被占用`)
  }
}

/** 异步等待指定毫秒 */
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 检测本地端口是否已被监听 */
function portInUse(port) {
  return new Promise((resolve) => {
    const server = require('net').createServer()
    server.once('error', () => resolve(true))
    server.once('listening', () => {
      server.close(() => resolve(false))
    })
    server.listen(port, '127.0.0.1')
  })
}

/** 通过 PowerShell 结束占用指定端口的进程 */
async function killPort(port) {
  await new Promise((resolve) => {
    const finder = spawn('powershell.exe', [
      '-NoProfile',
      '-Command',
      `Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }`
    ], { windowsHide: true, stdio: 'ignore' })
    finder.on('exit', () => resolve())
    finder.on('error', () => resolve())
    setTimeout(resolve, 4000)
  })
}

/** 解析打包或开发环境下的 backend 资源目录 */
function resolveBackendRoot(app) {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'backend')
  }
  return path.join(__dirname, 'resources', 'backend')
}

module.exports = {
  ServiceManager,
  resolveBackendRoot,
  DEFAULT_PORT
}
