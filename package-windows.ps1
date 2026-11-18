# Build Electron desktop + bundled Java service.
# Output: dist\desktop-build\OllamaChat-Setup-1.0.0.exe
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "scripts\stage-backend.ps1") -Root $root
if ($LASTEXITCODE -ne 0) { throw "stage-backend failed: $LASTEXITCODE" }

Set-Location (Join-Path $root "desktop")
$env:ELECTRON_MIRROR = "https://npmmirror.com/mirrors/electron/"
$env:ELECTRON_BUILDER_BINARIES_MIRROR = "https://npmmirror.com/mirrors/electron-builder-binaries/"
if (-not (Test-Path "node_modules")) {
    npm.cmd install
}
if (-not (Test-Path "node_modules\electron\dist\electron.exe")) {
    npm.cmd install-scripts approve electron | Out-Null
    node "node_modules\electron\install.js"
}
npm.cmd install-scripts approve electron-winstaller | Out-Null
npm.cmd run pack
if ($LASTEXITCODE -ne 0) { throw "electron-builder failed: $LASTEXITCODE" }

$installer = Get-ChildItem (Join-Path $root "dist\desktop-build") -Filter "OllamaChat-Setup-*.exe" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $installer) {
    throw "installer not found under dist\desktop-build"
}
Copy-Item $installer.FullName (Join-Path $root "dist\OllamaChat-Setup-1.0.0.exe") -Force
Write-Output "INSTALLER $($installer.FullName)"
Write-Output "COPY $(Join-Path $root 'dist\OllamaChat-Setup-1.0.0.exe')"
