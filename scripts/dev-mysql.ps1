# 本地开发数据库：以当前用户权限运行一个独立的 MySQL 实例（不需要管理员）
# 用法：. .\scripts\dev-mysql.ps1 [-Port 3307]
param(
    [int]$Port = 3307
)

$ErrorActionPreference = 'Stop'

$mysqld = 'E:\MySQL\MySQL Server 8.0\bin\mysqld.exe'
$mysql = 'E:\MySQL\MySQL Server 8.0\bin\mysql.exe'
if (-not (Test-Path $mysqld)) {
    throw "未找到 mysqld：$mysqld"
}

$base = Join-Path $env:LOCALAPPDATA 'tieringkv-mysql'
$data = Join-Path $base 'data'

if (-not (Test-Path $data)) {
    Write-Host "[dev-mysql] 初始化数据目录 $data" -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $data | Out-Null
    & $mysqld --no-defaults --initialize-insecure --datadir="$data" --console 2>&1 |
        Select-Object -Last 3 | ForEach-Object { Write-Host "[dev-mysql] $_" }
}

$listening = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($listening) {
    Write-Host "[dev-mysql] 端口 $Port 已在监听，跳过启动" -ForegroundColor Green
} else {
    Write-Host "[dev-mysql] 启动 mysqld（端口 $Port，数据目录 $data）" -ForegroundColor Green
    Start-Process -FilePath $mysqld -WindowStyle Hidden -ArgumentList @(
        '--no-defaults', "--datadir=$data", "--port=$Port",
        '--bind-address=127.0.0.1', '--console'
    ) | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 500
        if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { break }
    }
}

& $mysql -h 127.0.0.1 -P $Port --protocol=TCP -u root -e "CREATE DATABASE IF NOT EXISTS tiering_console DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
Write-Host "[dev-mysql] 就绪：jdbc:mysql://127.0.0.1:$Port/tiering_console (root / 空口令)" -ForegroundColor Green
