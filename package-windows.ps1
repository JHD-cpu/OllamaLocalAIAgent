# Build Electron desktop + bundled Java service.
# Output: dist\desktop-build\OllamaChat-Setup-<version>.exe
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $root "scripts\stage-backend.ps1") -Root $root
if ($LASTEXITCODE -ne 0) { throw "stage-backend failed: $LASTEXITCODE" }

$pkg = Get-Content (Join-Path $root "desktop\package.json") -Raw | ConvertFrom-Json
$version = [string]$pkg.version
if (-not $version) { throw "desktop package.json version missing" }

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
$stableCopy = Join-Path $root "dist\OllamaChat-Setup-$version.exe"
Copy-Item $installer.FullName $stableCopy -Force
Write-Output "INSTALLER $($installer.FullName)"
Write-Output "COPY $stableCopy"
