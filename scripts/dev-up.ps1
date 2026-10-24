# 本地一键拉起：MySQL(3307) + 内核(6379) + 控制面(8080) + 前端(5173)
# 用法：. .\scripts\dev-up.ps1        （dot-source 以继承环境变量）
#       .\scripts\dev-up.ps1 -SkipFrontend
param(
    [switch]$SkipFrontend,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repo 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-Port([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Wait-Port([int]$Port, [int]$Seconds = 40) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

# 1) JDK 21
. (Join-Path $PSScriptRoot 'env.ps1') | Out-Null
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

# 2) MySQL
if (Test-Port 3307) {
    Write-Host '[dev-up] MySQL 已在 3307 运行' -ForegroundColor Green
} else {
    . (Join-Path $PSScriptRoot 'dev-mysql.ps1') -Port 3307
}

# 3) 内核
if (Test-Port 6379) {
    Write-Host '[dev-up] 内核已在 6379 运行' -ForegroundColor Green
} else {
    if (-not $SkipBuild) {
        Push-Location $repo
        mvn -o -B -q -DskipTests package
        Pop-Location
    }
    # 不写死版本号：排除 original-* 后取最大的 jar（即 shade 后的可执行包）
    $jar = Get-ChildItem (Join-Path $repo 'target') -Filter 'tiering-kv-*.jar' |
        Where-Object { $_.Name -notlike 'original-*' } |
        Sort-Object Length -Descending | Select-Object -First 1
    if (-not $jar) { throw '未找到内核构件（先执行 mvn package）' }
    $jar = $jar.FullName
    Start-Process -FilePath $java -WorkingDirectory $repo -WindowStyle Hidden `
        -ArgumentList @('-cp', $jar, 'io.tieringkv.Main') `
        -RedirectStandardOutput (Join-Path $logDir 'core.out.log') `
        -RedirectStandardError (Join-Path $logDir 'core.err.log')
    if (-not (Wait-Port 6379 40)) { throw '内核启动失败，见 logs/core.err.log' }
    Write-Host '[dev-up] 内核已启动 :6379' -ForegroundColor Green
}

# 4) 控制面
if (Test-Port 8080) {
    Write-Host '[dev-up] 控制面已在 8080 运行' -ForegroundColor Green
} else {
    $consoleDir = Join-Path $repo 'console-server'
    $consoleJar = Get-ChildItem (Join-Path $consoleDir 'target') -Filter 'tiering-kv-console-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object Length -Descending | Select-Object -First 1
    if (-not $SkipBuild -or -not $consoleJar) {
        Push-Location $consoleDir
        # 优先离线构建（依赖已在本地仓库）；失败再走镜像源联网构建
        mvn -o -B -q -DskipTests package
        if ($LASTEXITCODE -ne 0) {
            Write-Host '[dev-up] 离线构建失败，改用阿里云镜像联网构建' -ForegroundColor Yellow
            mvn -s maven-settings.xml -B -q -DskipTests package
        }
        Pop-Location
        $consoleJar = Get-ChildItem (Join-Path $consoleDir 'target') -Filter 'tiering-kv-console-*.jar' |
            Where-Object { $_.Name -notlike '*.original' } |
            Sort-Object Length -Descending | Select-Object -First 1
    }
    Start-Process -FilePath $java -WorkingDirectory $consoleDir -WindowStyle Hidden `
        -ArgumentList @('-jar', $consoleJar.FullName) `
        -RedirectStandardOutput (Join-Path $logDir 'console.out.log') `
        -RedirectStandardError (Join-Path $logDir 'console.err.log')
    if (-not (Wait-Port 8080 90)) { throw '控制面启动失败，见 logs/console.err.log' }
    Write-Host '[dev-up] 控制面已启动 :8080（admin / admin123）' -ForegroundColor Green
}

# 5) 前端
if ($SkipFrontend) {
    Write-Host '[dev-up] 跳过前端' -ForegroundColor Yellow
} elseif (Test-Port 5173) {
    Write-Host '[dev-up] 前端已在 5173 运行' -ForegroundColor Green
} else {
    $webDir = Join-Path $repo 'console-web'
    if (-not (Test-Path (Join-Path $webDir 'node_modules'))) {
        Push-Location $webDir
        npm install --no-audit --no-fund
        Pop-Location
    }
    Start-Process -FilePath 'npm.cmd' -WorkingDirectory $webDir -WindowStyle Hidden `
        -ArgumentList @('run', 'dev') `
        -RedirectStandardOutput (Join-Path $logDir 'web.out.log') `
        -RedirectStandardError (Join-Path $logDir 'web.err.log')
    if (-not (Wait-Port 5173 60)) { throw '前端启动失败，见 logs/web.err.log' }
    Write-Host '[dev-up] 前端已启动 :5173' -ForegroundColor Green
}

Write-Host ''
Write-Host '  控制台   http://127.0.0.1:5173   账号 admin / admin123' -ForegroundColor Cyan
Write-Host '  控制面   http://127.0.0.1:8080/api/v1' -ForegroundColor Cyan
Write-Host '  数据面   127.0.0.1:6379 (RESP)' -ForegroundColor Cyan
Write-Host '  数据库   127.0.0.1:3307 (tiering_console, root/空)' -ForegroundColor Cyan
