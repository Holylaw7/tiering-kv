# 质量门禁：一条命令跑完控制面交付所需的全部校验
# 用法：.\scripts\quality-gates.ps1 [-Full]
#   -Full  额外执行内核全量测试（耗时数分钟）
param([switch]$Full)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$failed = @()

function Step([string]$Name, [scriptblock]$Action) {
    Write-Host ""
    Write-Host "=== $Name ===" -ForegroundColor Cyan
    $global:LASTEXITCODE = 0
    try {
        & $Action
    } catch {
        $script:failed += $Name
        Write-Host "  [FAIL] $Name : $($_.Exception.Message)" -ForegroundColor Red
        return
    }
    if ($LASTEXITCODE -ne 0) {
        $script:failed += $Name
        Write-Host "  [FAIL] $Name (exit=$LASTEXITCODE)" -ForegroundColor Red
    } else {
        Write-Host "  [PASS] $Name" -ForegroundColor Green
    }
}

. (Join-Path $PSScriptRoot 'env.ps1') | Out-Null

Step '内核编译' {
    Push-Location $repo
    mvn -o -B -q -DskipTests compile
    Pop-Location
}

if ($Full) {
    Step '内核全量测试' {
        Push-Location $repo
        mvn -o -B '-Dsurefire.excludedGroups=benchmark' test
        Pop-Location
    }
}

Step '控制面测试' {
    Push-Location (Join-Path $repo 'console-server')
    mvn -B -o test
    Pop-Location
}

Step '前端类型检查与构建' {
    Push-Location (Join-Path $repo 'console-web')
    npm run build
    Pop-Location
}

Step '接口端到端冒烟' {
    node (Join-Path $repo 'scripts\api-smoke.mjs')
}

Write-Host ""
if ($failed.Count -eq 0) {
    Write-Host '质量门禁全部通过' -ForegroundColor Green
    exit 0
}
Write-Host ("失败项：" + ($failed -join '、')) -ForegroundColor Red
exit 1
