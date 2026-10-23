# 环境固化：强制使用 JDK 21（项目唯一支持版本，maven.compiler.release=21）
# 用法（PowerShell）：
#   . .\scripts\env.ps1          # dot-source，影响当前会话
#   powershell -NoProfile -Command ". .\scripts\env.ps1; mvn -B test"
#
# 背景：本机 Machine PATH 中残留 JDK 17（C:\Program Files\Microsoft\jdk-17.0.7.7-hotspot\bin）
# 与 Oracle javapath，且 Machine PATH 优先于 User PATH，导致裸 java 指向 17。
# 该脚本在会话级把 JDK 21 置顶并剔除 17，无需管理员权限。

$ErrorActionPreference = 'Stop'

$Jdk21 = 'E:\Java\microsoft-jdk-21'
if (-not (Test-Path (Join-Path $Jdk21 'bin\java.exe'))) {
    throw "未找到 JDK 21：$Jdk21\bin\java.exe"
}

$env:JAVA_HOME = $Jdk21
$env:MAVEN_OPTS = ($env:MAVEN_OPTS + ' -Dfile.encoding=UTF-8').Trim()

# 剔除 JDK 17 / Oracle javapath 条目，再把 JDK 21 置顶
$kept = ($env:PATH -split ';') |
    Where-Object { $_ -and ($_ -notmatch 'jdk-17\.*\bin') -and ($_ -notmatch 'Oracle\\Java\\javapath') }
$env:PATH = (@($Jdk21 + '\bin') + $kept) -join ';'

Write-Host "[env] JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Green
& (Join-Path $Jdk21 'bin\java.exe') -version 2>&1 | ForEach-Object { Write-Host "[env] $_" -ForegroundColor Green }
