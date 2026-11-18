# Shared staging: frontend build → Maven installer jar → desktop/resources/backend (+ jlink runtime).
# Params:
#   -Root           repo root
#   -ReuseRuntime   if set, copy dist\runtime when present instead of always jlink
param(
    [Parameter(Mandatory = $true)]
    [string]$Root,
    [switch]$ReuseRuntime
)

$ErrorActionPreference = "Stop"
$jdk = "C:\Program Files\Java\jdk-25"
$jlink = Join-Path $jdk "bin\jlink.exe"
$java = Join-Path $jdk "bin\java.exe"
if (-not (Test-Path $jlink)) {
    throw "jlink not found: $jlink"
}

Set-Location (Join-Path $Root "frontend")
if (-not (Test-Path "node_modules")) {
    npm.cmd install
}
npm.cmd run build
if ($LASTEXITCODE -ne 0) { throw "npm run build failed: $LASTEXITCODE" }
if (-not (Test-Path "dist\index.html")) {
    throw "frontend build did not produce dist\index.html"
}

Set-Location (Join-Path $Root "backend")
& .\mvnw.cmd -q -Pinstaller package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "mvn package failed: $LASTEXITCODE" }
$jar = Join-Path $Root "backend\target\ollama-chat-0.0.1-SNAPSHOT.jar"
if (-not (Test-Path $jar)) {
    throw "jar not found: $jar"
}

$backendRes = Join-Path $Root "desktop\resources\backend"
if (Test-Path $backendRes) { Remove-Item $backendRes -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $backendRes "app\scripts") | Out-Null
Copy-Item $jar (Join-Path $backendRes "app\ollama-chat.jar")
Copy-Item (Join-Path $Root "backend\scripts\paddle_ocr.py") (Join-Path $backendRes "app\scripts\paddle_ocr.py")

$runtime = Join-Path $backendRes "runtime"
$cachedRuntime = Join-Path $Root "dist\runtime"
if ($ReuseRuntime -and (Test-Path (Join-Path $cachedRuntime "bin\java.exe"))) {
    Copy-Item $cachedRuntime $runtime -Recurse
}
else {
    $modules = & $java --list-modules | ForEach-Object { ($_ -split "@")[0] } | Where-Object { $_ -notlike "jdk.incubator*" }
    & $jlink --add-modules ($modules -join ",") --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
    if ($LASTEXITCODE -ne 0) { throw "jlink failed: $LASTEXITCODE" }
}

Write-Output "STAGED $backendRes"
