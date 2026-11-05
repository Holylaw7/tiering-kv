# Phase 7 生命周期验收：空实例 → 写入 → Flush → 冷读 → Compaction → 重启
# 用法：pwsh -File scripts/phase7-lifecycle.ps1
$ErrorActionPreference = 'Stop'
$repo = Resolve-Path (Join-Path $PSScriptRoot '..\..') | Select-Object -ExpandProperty Path
. (Join-Path $repo 'scripts\env.ps1') | Out-Null
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path $java)) { throw "未找到 JDK 21：$java" }
$jar = Join-Path $repo 'target\tiering-kv-5.1.0.jar'
$work = Join-Path $env:TEMP 'tk-phase7'
$port = 6381

function Stop-Kernel {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
  Start-Sleep -Seconds 2
}

function Start-Kernel {
  $proc = Start-Process -FilePath $java -WorkingDirectory $work -PassThru -WindowStyle Hidden -ArgumentList @(
    '-Xmx1g', '-cp', $jar, 'io.tieringkv.Main'
  ) -RedirectStandardOutput (Join-Path $work 'core.out.log') -RedirectStandardError (Join-Path $work 'core.err.log')
  for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    if ((Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) { return $proc }
  }
  throw '内核启动超时'
}

function Snapshot { & node (Join-Path $PSScriptRoot 'phase7-snapshot.mjs') $port | ConvertFrom-Json }

$checks = New-Object System.Collections.Generic.List[object]
function Check($name, $ok, $detail) {
  $checks.Add([pscustomobject]@{ name = $name; ok = [bool]$ok; detail = $detail })
  $flag = if ($ok) { 'PASS' } else { 'FAIL' }
  Write-Host ("{0} {1} — {2}" -f $flag, $name, $detail)
}

# 先停掉可能残留的实例，再清理工作目录（否则 Windows 上文件被锁）
Stop-Kernel
if (Test-Path $work) {
  for ($i = 0; $i -lt 5; $i++) {
    try { Remove-Item $work -Recurse -Force -ErrorAction Stop; break }
    catch { Start-Sleep -Seconds 2 }
  }
  if (Test-Path $work) { $work = "$work-" + (Get-Date -Format 'HHmmss') }
}
New-Item -ItemType Directory -Force -Path (Join-Path $work 'config') | Out-Null
$cfg = Get-Content (Join-Path $repo 'config\application.yaml') -Raw
$cfg = $cfg -replace 'port: 6379', "port: $port" -replace 'limit: 1073741824', 'limit: 25165824'
Set-Content -Path (Join-Path $work 'config\application.yaml') -Value $cfg -Encoding UTF8

$proc = Start-Kernel

# 1) 空实例
$s = Snapshot
Check '空实例上报 Tiering 段落' $s.hasTieringSection ''
Check '空实例 MemTable 为 0' ($s.memtableEntries -eq 0) ('entries=' + $s.memtableEntries)
Check '空实例无 SSTable' ($s.sstables -eq 0) ('sstables=' + $s.sstables)
Check '空实例 Flush/Compaction 为 0' (($s.flushCount -eq 0) -and ($s.compactionCount -eq 0)) ('flush=' + $s.flushCount + ' compaction=' + $s.compactionCount)

# 2) 写入 → MemTable 增长
& node (Join-Path $PSScriptRoot 'tiering-rounds.mjs') $port 1 60000 0 | Out-Null
$s = Snapshot
Check '写入后 MemTable 增长' ($s.memtableBytes -gt 0 -and $s.memtableEntries -gt 0) ('entries=' + $s.memtableEntries + ' bytes=' + $s.memtableBytes)

# 3) 多轮写入触发 Flush
& node (Join-Path $PSScriptRoot 'tiering-rounds.mjs') $port 8 200000 1500 | Out-Null
Start-Sleep -Seconds 2
$s = Snapshot
Check '触发 Flush 并生成 SSTable' ($s.flushCount -ge 1 -and $s.sstables -ge 1) ('flush=' + $s.flushCount + ' sstables=' + $s.sstables + ' cold=' + $s.coldBytes)

# 4) 冷读 → BlockCache 命中
& node (Join-Path $PSScriptRoot 'tiering-read-warm.mjs') $port 30000 1000000 | Out-Null
Start-Sleep -Seconds 1
$s = Snapshot
Check '冷读产生 BlockCache 命中/未命中' (($s.cacheHits + $s.cacheMisses) -gt 0) ('hits=' + $s.cacheHits + ' misses=' + $s.cacheMisses)

# 5) Compaction
$compaction = $s.compactionCount
if ($compaction -eq 0) {
  & node (Join-Path $PSScriptRoot 'tiering-rounds.mjs') $port 10 250000 1200 | Out-Null
  Start-Sleep -Seconds 3
  $s = Snapshot
}
Check '触发 Compaction' ($s.compactionCount -ge 1) ('compaction=' + $s.compactionCount)

$beforeRestart = $s

# 6) 重启：gauge 从 WAL/Manifest 重建，累计计数器归零
Stop-Kernel
$proc = Start-Kernel
Start-Sleep -Seconds 2
$s = Snapshot
Check '重启后仍上报 Tiering 段落' $s.hasTieringSection ''
# 重启会重放 WAL，恢复过程本身可能再触发一次 Flush，因此只断言"冷层数据仍在"
Check '重启后冷层数据保留' (($s.sstables -ge 1) -and ($s.coldBytes -gt 0)) ('before=' + $beforeRestart.sstables + ' after=' + $s.sstables + ' cold=' + $s.coldBytes)
# 累计计数从 0 重新开始；恢复期最多发生一次 Flush，Compaction 不应凭空出现
Check '重启后累计计数从零重建' (($s.compactionCount -eq 0) -and ($s.flushCount -le 1)) ('flush=' + $s.flushCount + ' compaction=' + $s.compactionCount)
Check '重启后数据仍可读（DBSIZE>0）' ($s.totalKeys -gt 0) ('keys=' + $s.totalKeys)

Stop-Kernel
$failed = $checks | Where-Object { -not $_.ok }
Write-Host ''
Write-Host ("总结：{0} 项，通过 {1}，失败 {2}" -f $checks.Count, ($checks.Count - $failed.Count), $failed.Count)
if ($failed.Count -gt 0) { exit 1 }