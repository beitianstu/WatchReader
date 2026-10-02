# 安装 APK 到华为 Watch3/4 系列（自动冻结/解冻"应用安装器"）
#
# 背景：这类手表默认禁止 ADB 安装第三方应用，直接 pm install 会报
#       INSTALL_FAILED_ABORTED: User rejected permissions
#       日志里能看到的真实原因是 PackageInstaller 的：
#       "InstallStart:try to install in watch, not allow install unknown apps"
# 绕过方式：临时冻结 com.android.packageinstaller（禁用其校验），装完立刻解冻。
# 关键：装完到重启前**必须解冻**，否则系统的安装界面会不可用。
#
# 用法：
#   & .\tools\install-apk.ps1                                  # 用默认 APK
#   & .\tools\install-apk.ps1 -Apk D:\x\y.apk -Target 192.168.1.5:5555
param(
    [string]$Apk = 'C:\Users\fan18\RiderProjects\WatchReader\android\build\apk\WatchReader.apk',
    [string]$Adb = 'D:\Android\android-sdk\platform-tools\adb.exe',
    [string]$Target = '',
    [switch]$SkipUnfreeze
)

$ErrorActionPreference = 'Continue'

if (-not (Test-Path $Apk)) { throw "找不到 APK：$Apk" }
if (-not (Test-Path $Adb)) { throw "找不到 adb：$Adb" }

if (-not $Target) {
    $devices = & $Adb devices | Select-String -Pattern '\tdevice' | ForEach-Object { ($_ -split '\s+')[0] }
    if (-not $devices) { throw "没有已连接的设备（先 adb connect <IP>:5555）" }
    $Target = $devices[0]
}
Write-Host "目标设备：$Target" -ForegroundColor Cyan

# 自动保持"亮屏 + Wi-Fi"（幂等；不会覆盖已有会话的原始值记录）
# 由 tools\adb-session.ps1 stop 统一还原。
. (Join-Path $PSScriptRoot 'adb-session.ps1') -Quiet
Start-KeepAliveSession

function Invoke-OnDevice([string]$command) {
    & $Adb -s $Target shell $command 2>&1
}

$installer = 'com.android.packageinstaller'
$frozen = $false

# 注意：这里**不要**再调 `svc power stayon true`。
# Android 的该命令会把 STAY_ON_WHILE_PLUGGED_IN 偏好清成 0，从而把会话脚本设好的
# `stay_on_while_plugged_in=7` 冲掉（实测就是这个问题）。保持亮屏统一由 adb-session.ps1 负责。

try {
    Write-Host "冻结 $installer ..." -ForegroundColor Yellow
    Invoke-OnDevice "pm disable-user --user 0 $installer" | Out-Null
    $state = (Invoke-OnDevice "dumpsys package $installer | grep -m1 'enabled='") -join ' '
    if ($state -notmatch 'enabled=3') {
        Write-Warning "冻结状态看起来不对：$state —— 仍继续尝试安装"
    } else {
        $frozen = $true
    }

    Write-Host "安装 $Apk ..." -ForegroundColor Cyan
    & $Adb -s $Target install -r $Apk 2>&1 | ForEach-Object { Write-Host "    $_" }

    $installed = (Invoke-OnDevice "pm list packages | grep -c com.watchreader") -join ''
    if ($installed.Trim() -eq '1') {
        Write-Host "安装成功：com.watchreader" -ForegroundColor Green
    } else {
        Write-Warning "安装结果不确定，请检查上面的输出"
    }
}
finally {
    if (-not $SkipUnfreeze) {
        Write-Host "解冻 $installer ..." -ForegroundColor Yellow
        Invoke-OnDevice "pm enable --user 0 $installer" | Out-Null
        $state = (Invoke-OnDevice "dumpsys package $installer | grep -m1 'enabled='") -join ' '
        if ($state -match 'enabled=1') {
            Write-Host "已解冻（enabled=1），系统安装界面恢复可用" -ForegroundColor Green
        } else {
            Write-Warning "解冻状态异常：$state —— 请手动执行 adb shell pm enable --user 0 $installer"
        }
    } else {
        Write-Warning "按要求跳过解冻：请务必在重启手表前手动执行 adb shell pm enable --user 0 $installer"
    }
    # 同理不要在这里 `svc power stayon false`：它会把 stay_on_while_plugged_in 写回 0，
    # 破坏会话的"保持亮屏"。还原由 adb-session.ps1 stop 统一负责。
}
