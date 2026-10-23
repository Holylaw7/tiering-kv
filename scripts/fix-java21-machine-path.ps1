# 一次性修复：把系统级 Machine PATH 中的 JDK 17 替换为 JDK 21（需要管理员权限）
# 用法：右键“以管理员身份运行” PowerShell，执行：
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\fix-java21-machine-path.ps1
#
# 说明：Machine PATH 优先于 User PATH，因此仅改 User PATH 无法让裸 java 指向 21。
# 脚本会先备份原 Machine PATH 到 scripts/backup/machine-path-<timestamp>.txt。

$ErrorActionPreference = 'Stop'

$principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw '需要管理员权限：请以管理员身份重新运行 PowerShell 后再执行本脚本。'
}

$Jdk21 = 'E:\Java\microsoft-jdk-21'
if (-not (Test-Path (Join-Path $Jdk21 'bin\java.exe'))) {
    throw "未找到 JDK 21：$Jdk21\bin\java.exe"
}

$backupDir = Join-Path $PSScriptRoot 'backup'
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'

$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
Set-Content -Path (Join-Path $backupDir "machine-path-$stamp.txt") -Value $machinePath -Encoding UTF8

$entries = $machinePath -split ';' | Where-Object { $_ }
$filtered = $entries | Where-Object {
    ($_ -notmatch 'jdk-17\.*\\bin$') -and ($_ -notmatch 'Oracle\\Java\\javapath') -and ($_ -ne (Join-Path $Jdk21 'bin'))
}
$newPath = (@((Join-Path $Jdk21 'bin')) + $filtered) -join ';'

[Environment]::SetEnvironmentVariable('Path', $newPath, 'Machine')
[Environment]::SetEnvironmentVariable('JAVA_HOME', $Jdk21, 'Machine')

Write-Host "[done] 系统 PATH 已更新，JAVA_HOME=$Jdk21" -ForegroundColor Green
Write-Host "[done] 原 PATH 备份：$backupDir\machine-path-$stamp.txt" -ForegroundColor Green
Write-Host '[done] 请新开一个终端验证： java -version' -ForegroundColor Green
