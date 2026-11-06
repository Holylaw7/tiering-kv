# F3 数据库断连（专用 MySQL，隔离验证同进程连接池自愈）
$ErrorActionPreference = 'Stop'
$scripts = $PSScriptRoot
$repo = Resolve-Path (Join-Path $scripts '..\..\..') | Select-Object -ExpandProperty Path
$port = 3307
$node = 'node'

function Test-Port([int]$p) {
  return [bool](Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue)
}

Write-Host '--- 阶段 1：基线 ---'
& $node (Join-Path $scripts 'f3-stage.mjs') baseline 2>&1 | ForEach-Object { $_ }
if ($LASTEXITCODE -ne 0) { throw '基线失败：DB 未就绪' }

$owner = (Get-NetTCPConnection -LocalPort $port -State Listen).OwningProcess
Write-Host "--- 阶段 2：停止专用 MySQL（pid=$owner，datadir=%LOCALAPPDATA%\tieringkv-mysql）---"
Stop-Process -Id $owner -Force
Start-Sleep -Seconds 2
if (Test-Port $port) { throw 'MySQL 仍在监听，停止失败' }

Write-Host '--- 阶段 3：DB 断连期间请求 ---'
& $node (Join-Path $scripts 'f3-stage.mjs') during 2>&1 | ForEach-Object { $_ }
$duringExit = $LASTEXITCODE

Write-Host '--- 阶段 4：恢复专用 MySQL ---'
. (Join-Path $repo 'scripts\dev-mysql.ps1') -Port $port
for ($i = 0; $i -lt 30; $i++) {
  if (Test-Port $port) { break }
  Start-Sleep -Milliseconds 500
}
if (-not (Test-Port $port)) { throw 'MySQL 未能恢复' }

Write-Host '--- 阶段 5：不重启控制面，验证连接池自愈 ---'
& $node (Join-Path $scripts 'f3-stage.mjs') after 2>&1 | ForEach-Object { $_ }
$afterExit = $LASTEXITCODE

Write-Host '--- 阶段 6：健康检查 ---'
$health = try { (Invoke-WebRequest -Uri 'http://127.0.0.1:8080/actuator/health' -UseBasicParsing -TimeoutSec 10).StatusCode } catch { 0 }
if ($health -eq 200) { Write-Host 'PASS 控制面 health 端点可用 — 200' } else { Write-Host "FAIL 控制面 health 端点可用 — $health" }

foreach ($code in @($duringExit, $afterExit)) {
  if ($code -ne 0) { exit 1 }
}
if ($health -ne 200) { exit 1 }
