# F9 回归：验证 install-apk.ps1 式的"点源 + 传参"能把目标设备与 adb 路径带进 adb-session.ps1。
#
# 背景：adb-session.ps1 的参数刻意叫 $Device / $AdbPath（不叫 $Target，避免点源覆盖调用方
# 变量）。旧版 install-apk.ps1 只写了 `-Quiet`，于是会话退回 env.ps1 的默认地址
# （WATCH_ADB_TARGET），换表或用了 -Target 时 Start-KeepAliveSession 会连到错误地址、
# 重试 3 次后抛错，安装直接中止。
#
# 本脚本只**加载函数**（点源时 InvocationName 为 '.'，会跳过 start/stop/status 主逻辑），
# 不连接、不修改任何设备状态，也不要求 adb 存在。
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\test-session-params.ps1

$ErrorActionPreference = 'Continue'
$repo = Split-Path $PSScriptRoot -Parent
. (Join-Path $repo 'tools\env.ps1')

$fail = 0

# 1) 与修复后的 install-apk.ps1 调用完全一致（-Quiet -Device <目标> -AdbPath <adb>）
$Target = '10.0.0.9:5555'
$Adb = 'D:\fake\adb.exe'
. (Join-Path $repo 'tools\adb-session.ps1') -Quiet -Device $Target -AdbPath $Adb

if ($script:SessionTarget -eq $Target) {
    Write-Host "PASS  会话目标 = 传入的 -Device（$Target）" -ForegroundColor Green
} else {
    Write-Host "FAIL  会话目标 = $($script:SessionTarget)，期望 $Target" -ForegroundColor Red
    $fail++
}
if ($script:SessionAdb -eq $Adb) {
    Write-Host "PASS  会话 adb = 传入的 -AdbPath（$Adb）" -ForegroundColor Green
} else {
    Write-Host "FAIL  会话 adb = $($script:SessionAdb)，期望 $Adb" -ForegroundColor Red
    $fail++
}

# 2) Environment fallback is explicit; never connect to a built-in private address.
$previousTarget = $env:WATCH_ADB_TARGET
try {
    $env:WATCH_ADB_TARGET = 'test-watch:5555'
    . (Join-Path $repo 'tools\adb-session.ps1') -Quiet -AdbPath $Adb
    if ($script:SessionTarget -eq 'test-watch:5555') {
        Write-Host 'PASS  Environment device target is honored' -ForegroundColor Green
    } else { $fail++ }
} finally {
    $env:WATCH_ADB_TARGET = $previousTarget
}

Write-Host ""
if ($fail -eq 0) {
    Write-Host "test-session-params 通过 ✓" -ForegroundColor Green
    exit 0
} else {
    Write-Host "test-session-params 失败 ✗" -ForegroundColor Red
    exit 1
}
