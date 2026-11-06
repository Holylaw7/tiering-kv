# Phase 10 全量 E2E 复跑（业务闭环 + 故障注入）
$ErrorActionPreference = 'Stop'
$scripts = $PSScriptRoot
$repo = Resolve-Path (Join-Path $scripts '..\..\..') | Select-Object -ExpandProperty Path
$coreJar = Join-Path $repo 'target\tiering-kv-5.1.0.jar'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { $java = 'java' }

$results = New-Object System.Collections.Generic.List[object]
function Run-Node($label, $file, $stage) {
  Write-Host ""
  Write-Host "=== $label ===" -ForegroundColor Cyan
  if ($stage) { & node (Join-Path $scripts $file) $stage 2>&1 | ForEach-Object { $_ } }
  else { & node (Join-Path $scripts $file) 2>&1 | ForEach-Object { $_ } }
  $code = $LASTEXITCODE
  $results.Add([pscustomobject]@{ Case = $label; Exit = $code })
}

Run-Node 'E2E-1 认证与权限' 'e2e-01-auth.mjs'
Run-Node 'E2E-2 实例管理' 'e2e-02-instance.mjs'
Run-Node 'E2E-3 键空间' 'e2e-03-keyspace.mjs'
Run-Node 'E2E-4 在线终端' 'e2e-04-terminal.mjs'
Run-Node 'E2E-5 备份恢复' 'e2e-05-backup.mjs'
Run-Node 'E2E-6 告警' 'e2e-06-alert.mjs'
Run-Node 'E2E-7 审计' 'e2e-07-audit.mjs'
Run-Node 'F1 实例离线' 'faults-api.mjs' 'f1'
Run-Node 'F4 Token 校验' 'faults-api.mjs' 'f4'

Write-Host ""
Write-Host "=== F2 崩溃恢复（写入 → 等待 fsync → 强杀 → 重启 → 校验）===" -ForegroundColor Cyan
& node (Join-Path $scripts 'faults-api.mjs') 'pre' | ForEach-Object { $_ }
$pid6383 = (Get-NetTCPConnection -LocalPort 6383 -State Listen).OwningProcess
Stop-Process -Id $pid6383 -Force
Start-Sleep -Seconds 2
Start-Process -FilePath $java -WorkingDirectory (Join-Path $env:TEMP 'tk-phase10') -WindowStyle Hidden `
  -ArgumentList @('-Xmx512m', '-cp', $coreJar, 'io.tieringkv.Main') `
  -RedirectStandardOutput (Join-Path $env:TEMP 'tk-phase10\core.out.log') `
  -RedirectStandardError (Join-Path $env:TEMP 'tk-phase10\core.err.log') | Out-Null
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Milliseconds 500
  if (Get-NetTCPConnection -LocalPort 6383 -State Listen -ErrorAction SilentlyContinue) { break }
}
& node (Join-Path $scripts 'faults-api.mjs') 'post' | ForEach-Object { $_ }
$results.Add([pscustomobject]@{ Case = 'F2 崩溃恢复'; Exit = $LASTEXITCODE })

Write-Host ""
Write-Host "=== 汇总 ===" -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String -Width 80 | Write-Host
$failed = $results | Where-Object { $_.Exit -ne 0 }
if ($failed.Count -gt 0) {
  Write-Host ("失败用例：" + ($failed.Case -join ', ')) -ForegroundColor Red
  exit 1
}
Write-Host ("全部通过：" + $results.Count + " 个用例") -ForegroundColor Green
