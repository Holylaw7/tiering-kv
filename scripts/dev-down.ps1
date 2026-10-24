# 停止 dev-up 拉起的进程（按端口 / 命令行匹配，避免误杀其他程序）
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot

function Stop-ByPort([int]$Port, [string]$Name) {
    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    foreach ($connection in $connections) {
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
        if ($process) {
            Stop-Process -Id $process.Id -Force
            Write-Host "[dev-down] 已停止 $Name (pid=$($process.Id), port=$Port)" -ForegroundColor Yellow
        }
    }
}

Stop-ByPort 5173 '前端'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'tiering-kv-console' } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force
        Write-Host "[dev-down] 已停止控制面 (pid=$($_.ProcessId))" -ForegroundColor Yellow
    }
Stop-ByPort 6379 '内核'
Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" |
    Where-Object { $_.CommandLine -match 'tieringkv-mysql' } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force
        Write-Host "[dev-down] 已停止 MySQL (pid=$($_.ProcessId))" -ForegroundColor Yellow
    }
Write-Host '[dev-down] 完成' -ForegroundColor Green
