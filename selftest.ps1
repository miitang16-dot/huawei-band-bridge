# bandcore 纯协议层离线自测（Windows / PowerShell 5.1+ 或 pwsh）
#
# 只用 JDK 21 的 javac/java：不联网、不跑 Gradle、不需要 Android SDK、不需要手环。
# 做的事：把 bandcore 里不依赖 Android 设备/API 的协议源码编到 build/selftest/，
# 再编 bandcore/src/test/java 下的自测类，最后跑 tools/selftest/SelfTestRunner.java。
#
# 覆盖什么、测不到什么：docs/TESTING.md
# 退出码 0 = 全过；非 0 = 有失败或编译失败。

[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($JavaHome) {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $java = Join-Path $JavaHome 'bin\java.exe'
} else {
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javacCmd -or -not $javaCmd) {
        throw "找不到 javac/java：请设置 JAVA_HOME（需要 JDK 17+，项目用 JDK 21）或把 JDK 加进 PATH"
    }
    $javac = $javacCmd.Source
    $java = $javaCmd.Source
}

if (-not (Test-Path $javac)) { throw "javac 不存在：$javac" }
if (-not (Test-Path $java)) { throw "java 不存在：$java" }

$out = Join-Path $root 'build\selftest'
$stubOut = Join-Path $out 'stubs'
$mainOut = Join-Path $out 'main'
$testOut = Join-Path $out 'test'

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $stubOut, $mainOut, $testOut | Out-Null

function Get-Sources([string]$path) {
    @(Get-ChildItem -Path $path -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
}

Write-Host "== [1/4] compile stubs (android.* / org.json 的最小替身)" -ForegroundColor Cyan
$stubSources = Get-Sources (Join-Path $root 'tools\selftest\stubs')
& $javac -encoding UTF-8 --release 17 -d $stubOut @stubSources
if ($LASTEXITCODE -ne 0) { throw "stub 编译失败" }

Write-Host "== [2/4] compile pure bandcore sources" -ForegroundColor Cyan
# 只编「不依赖 Android 设备/Context/Bluetooth」的部分：
#   proto/** 整个包（TLV / 帧 / CRC / 时间换算 / 序列解析 / 各报文类）
#   Probe（诊断日志，只用 android.util.Log）
#   transport/PacketAssembler（攒包分发；HuaweiLink 需要真蓝牙，不编）
# bandcore 里其余类（BandSession / HuaweiLink / AuthFlow / data/**）需要 Context、
# BluetoothGatt 或 SharedPreferences，属于「必须上设备才能测」的那一半。
$mainSources = Get-Sources (Join-Path $root 'bandcore\src\main\java\io\github\bandbridge\core\proto')
$mainSources += Join-Path $root 'bandcore\src\main\java\io\github\bandbridge\core\Probe.java'
$mainSources += Join-Path $root 'bandcore\src\main\java\io\github\bandbridge\core\transport\PacketAssembler.java'
& $javac -encoding UTF-8 --release 17 -cp $stubOut -d $mainOut @mainSources
if ($LASTEXITCODE -ne 0) { throw "bandcore 源码编译失败" }

Write-Host "== [3/4] compile self-tests" -ForegroundColor Cyan
$testSources = Get-Sources (Join-Path $root 'bandcore\src\test\java')
$testSources += Join-Path $root 'tools\selftest\SelfTestRunner.java'
& $javac -encoding UTF-8 --release 17 -cp "$mainOut;$stubOut" -d $testOut @testSources
if ($LASTEXITCODE -ne 0) { throw "自测类编译失败" }

Write-Host "== [4/4] run self-tests" -ForegroundColor Cyan
& $java -cp "$testOut;$mainOut;$stubOut" SelfTestRunner
exit $LASTEXITCODE
