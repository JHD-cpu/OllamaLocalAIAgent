$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
if (-not (Test-Path (Join-Path $root "desktop"))) {
    $root = "F:\AITEST"
}

& (Join-Path $root "scripts\stage-backend.ps1") -Root $root -ReuseRuntime
if ($LASTEXITCODE -ne 0) { throw "stage-backend failed: $LASTEXITCODE" }

Set-Location (Join-Path $root "desktop")
$env:ELECTRON_MIRROR = "https://npmmirror.com/mirrors/electron/"
$env:ELECTRON_BUILDER_BINARIES_MIRROR = "https://npmmirror.com/mirrors/electron-builder-binaries/"
npm.cmd install
if ($LASTEXITCODE -ne 0) { throw "desktop npm install failed" }
if (-not (Test-Path "node_modules\electron\dist\electron.exe")) {
    npm.cmd install-scripts approve electron | Out-Null
    node "node_modules\electron\install.js"
}
if (-not (Test-Path "node_modules\electron\dist\electron.exe")) {
    throw "electron binary missing"
}
Write-Output "READY"
