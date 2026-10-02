# 调试会话：自动"保持亮屏 + 保持 Wi-Fi"，结束后还原
#
#   & .\tools\adb-session.ps1 start      # 连表 + 保持亮屏/Wi-Fi（先记录原值）
#   & .\tools\adb-session.ps1 status     # 看当前状态
#   & .\tools\adb-session.ps1 stop       # 还原到 start 之前的状态
#
# 设计要点：
#   · start 先把**原始值**存进 JSON，stop 严格按原值还原（不是"猜默认值"）；
#   · stop 幂等、可重复调用；没有会话文件时不做任何改动；
#   · build-apk / install-apk 会自动 start（幂等，不覆盖已有基线），
#     所以"每次连上 adb 就自动保持"，最后由你手动 stop 统一还原。
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'status')]
    [string]$Action = 'start',

    # 同理不叫 $Adb：点源会覆盖调用方的 $Adb。
    [string]$AdbPath = '',
    # 注意：这个参数**不能叫 $Target** —— 点源本脚本时 param 会绑定到调用方作用域，
    # 把调用方的 $Target 强制成 [string]，从而导致 $list[0] 变成字符串首字符（实测踩过）。
    [string]$Device = '',

    # 保持亮屏的时长（毫秒）。手表电池供电时系统仍可能熄屏，所以给得大些；
    # App 自己也会在阅读页用 FLAG_KEEP_SCREEN_ON。
    [int]$ScreenOffTimeoutMs = 1800000,

    [switch]$Quiet,

    # 默认**不还原** Wi-Fi 休眠策略。
    # 原因：还原成 1（息屏关 Wi-Fi）会让无线 adb 立刻断线，之后就再也连不上、
    # 连"恢复"都做不到（实测踩过：还原后设备直接变成不可达）。
    # 想连这项一起还原就显式加 -RestoreWifiPolicy。
    [switch]$RestoreWifiPolicy
)

. (Join-Path $PSScriptRoot 'env.ps1')
# 脚本级变量：点源时在同一会话内复用
if (-not $Device) { $Device = Get-WrEnv 'Target' }
if (-not $AdbPath) { $AdbPath = Get-WrEnv 'Adb' }
$script:SessionAdb = $AdbPath
$script:SessionTarget = $Device
$script:SessionTimeoutMs = $ScreenOffTimeoutMs
$script:SessionQuiet = [bool]$Quiet
$script:sessionStateFile = Join-Path $env:TEMP 'watchreader-adb-session.json'

function Session-Say([string]$msg, [string]$color = 'Gray') {
    if (-not $script:SessionQuiet) { Write-Host $msg -ForegroundColor $color }
}

function Session-OnDevice([string]$cmd) {
    # dumpsys 通过管道接 grep 时，grep 提前退出会让 adb 收到 SIGPIPE，
    # PS 里表现为 "Failed to write while dumping service ...: Broken pipe"。
    # 那是**无害**噪音（数据已取到），所以只保留 stdout。
    $out = & $script:SessionAdb -s $script:SessionTarget shell $cmd 2>$null
    return ($out | Out-String).Trim()
}

function Test-SessionConnected {
    param([int]$Retries = 3, [int]$RetryDelaySec = 5)

    if (-not (Test-Path $script:SessionAdb)) { throw "找不到 adb：$($script:SessionAdb)" }

    for ($i = 1; $i -le $Retries; $i++) {
        $state = Get-WrDeviceState $script:SessionAdb $script:SessionTarget
        if ($state -eq 'device') { return $true }

        if ($state -eq 'offline') {
            Session-Say "设备处于 offline，重新连接（第 $i/$Retries 次）..." 'Yellow'
            & $script:SessionAdb disconnect $script:SessionTarget 2>&1 | Out-Null
            Start-Sleep -Seconds 1
        } else {
            Session-Say "连接 $($script:SessionTarget)（第 $i/$Retries 次）..." 'Yellow'
        }

        & $script:SessionAdb connect $script:SessionTarget 2>&1 | Out-Null
        Start-Sleep -Seconds $RetryDelaySec
    }

    if ((Get-WrDeviceState $script:SessionAdb $script:SessionTarget) -eq 'device') { return $true }

    throw ("设备不可用：{0}`n  排查顺序：`n    1) 抬起手腕点亮手表（息屏后 Wi-Fi 可能已断）`n    2) 下拉快捷开关确认 Wi-Fi 是开着的`n    3) 确认手表与本机在同一网段`n    4) 必要时重新执行：& .\tools\adb-session.ps1 start" -f $script:SessionTarget)
}
function Read-SessionOrigin {
    if (-not (Test-Path $script:sessionStateFile)) { return $null }
    return (Get-Content $script:sessionStateFile -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Save-SessionOrigin($origin) {
    ($origin | ConvertTo-Json) | Set-Content -Path $script:sessionStateFile -Encoding UTF8
}

function Start-KeepAliveSession {
    Test-SessionConnected | Out-Null

    if (Test-Path $script:sessionStateFile) {
        Session-Say "已有会话基线（不覆盖）：$($script:sessionStateFile)" 'DarkGray'
    } else {
        $origin = [ordered]@{
            target           = $script:SessionTarget
            screenOffTimeout = Session-OnDevice 'settings get system screen_off_timeout'
            stayOnPlugged    = Session-OnDevice 'settings get global stay_on_while_plugged_in'
            wifiSleepPolicy  = Session-OnDevice 'settings get global wifi_sleep_policy'
            capturedAt       = (Get-Date).ToString('s')
        }
        Save-SessionOrigin $origin
        Session-Say "已记录原始状态 → $($script:sessionStateFile)" 'DarkGray'
        Session-Say ("  息屏超时={0}  插电常亮={1}  Wi-Fi休眠={2}" -f `
            $origin.screenOffTimeout, $origin.stayOnPlugged, $origin.wifiSleepPolicy) 'DarkGray'
    }

    Session-OnDevice "settings put system screen_off_timeout $($script:SessionTimeoutMs)" | Out-Null
    Session-OnDevice 'settings put global stay_on_while_plugged_in 7' | Out-Null   # 1=AC 2=USB 4=无线
    Session-OnDevice 'settings put global wifi_sleep_policy 2' | Out-Null           # 2=永不休眠
    Session-OnDevice 'svc power stayon true' | Out-Null
    Session-OnDevice 'input keyevent KEYCODE_WAKEUP' | Out-Null

    Session-Say "已开启保持：" 'Green'
    Session-Say ("  息屏超时 {0} ms / 插电常亮 7 / Wi-Fi 永不休眠 / 已唤醒屏幕" -f $script:SessionTimeoutMs)
    Session-Say "  结束工作时执行： & .\tools\adb-session.ps1 stop" 'Yellow'
}

function Stop-KeepAliveSession {
    if (-not (Test-Path $script:sessionStateFile)) {
        Session-Say "没有会话文件，说明当前不是自动化会话状态；不做任何改动。" 'Yellow'
        return
    }
    Test-SessionConnected | Out-Null
    $origin = Read-SessionOrigin

    $timeout = if ("$($origin.screenOffTimeout)" -match '^\d+$') { [int]$origin.screenOffTimeout } else { 30000 }
    $stayOn = if ("$($origin.stayOnPlugged)" -match '^\d+$') { [int]$origin.stayOnPlugged } else { 0 }
    $wifi = if ("$($origin.wifiSleepPolicy)" -match '^\d+$') { [int]$origin.wifiSleepPolicy } else { 1 }

    Session-OnDevice "settings put system screen_off_timeout $timeout" | Out-Null
    Session-OnDevice "settings put global stay_on_while_plugged_in $stayOn" | Out-Null

    if ($RestoreWifiPolicy) {
        # 危险操作：如果原值是 1，这条会立刻让无线 adb 断线
        Session-OnDevice "settings put global wifi_sleep_policy $wifi" | Out-Null
        Session-Say ("  Wi-Fi 休眠已还原为 {0}（若为 1，息屏后 adb 可能立刻断线）" -f $wifi) 'Yellow'
    } else {
        Session-Say ("  Wi-Fi 休眠保持为 2（未还原；需要还原请加 -RestoreWifiPolicy）") 'DarkGray'
    }
    Session-OnDevice 'svc power stayon false' | Out-Null

    Session-Say "已还原：" 'Green'
    Session-Say ("  息屏超时={0} ms  插电常亮={1}  Wi-Fi休眠={2}" -f $timeout, $stayOn, $wifi)
    if ($origin.capturedAt) { Session-Say ("  （原始状态记录于 {0}）" -f $origin.capturedAt) 'DarkGray' }

    Remove-Item $script:sessionStateFile -Force -ErrorAction SilentlyContinue
    Session-Say "会话文件已删除。" 'DarkGray'
}

function Show-SessionStatus {
    Test-SessionConnected | Out-Null
    Session-Say "设备      : $(((& $script:SessionAdb devices 2>&1) | Select-String $script:SessionTarget) -join '')" 'Cyan'
    Session-Say "屏幕状态  : $(Session-OnDevice "dumpsys power | grep -m1 'mWakefulness='")"
    Session-Say "息屏超时  : $(Session-OnDevice 'settings get system screen_off_timeout') ms"
    Session-Say "插电常亮  : $(Session-OnDevice 'settings get global stay_on_while_plugged_in')"
    Session-Say "Wi-Fi休眠 : $(Session-OnDevice 'settings get global wifi_sleep_policy')  (2=永不休眠)"
    Session-Say "Wi-Fi 状态: $(Session-OnDevice "dumpsys wifi | grep -m1 'Wi-Fi is'")"
    if (Test-Path $script:sessionStateFile) {
        Session-Say "会话文件  : $($script:sessionStateFile)（存在 = 有未还原的会话）" 'Yellow'
    } else {
        Session-Say "会话文件  : 无（当前不是自动化会话状态）"
    }
}

# ============================================================================
# 主逻辑：只有**直接运行**本脚本才执行。
# 被其它脚本点源（. .\adb-session.ps1）时跳过，只加载上面的函数 ——
# 否则点源会把 start/stop 动作误触发一遍。
# ============================================================================
if ($MyInvocation.InvocationName -ne '.') {
    switch ($Action) {
        'start' { Start-KeepAliveSession }
        'stop' { Stop-KeepAliveSession }
        'status' { Show-SessionStatus }
    }
}
