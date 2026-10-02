# 让手表息屏后不断开 Wi-Fi（华为 Watch3/4 系列）
#
# 背景：这类手表的默认休眠策略是"熄屏后关闭 Wi-Fi"（wifi_sleep_policy=1），
#      于是锁屏/息屏后无线 adb 会断、后台同步也会停。
# 改成 2 = 永不休眠，Wi-Fi 在熄屏后照常保持。
#
# 用法：
#   & .\tools\wifi-stay-awake.ps1                 # 设为"永不休眠"（推荐）
#   & .\tools\wifi-stay-awake.ps1 -Policy 1       # 恢复默认（熄屏关 Wi-Fi）
#   & .\tools\wifi-stay-awake.ps1 -Verify         # 只检测当前策略与 Wi-Fi 状态
#
# 说明：这是**设备级设置**，改完一直有效（重启也保留），不需要 App 参与。
#      系统更新后可能被重置，届时重跑一次即可。
param(
    [ValidateSet(0, 1, 2)]
    [int]$Policy = 2,
    [string]$Adb = 'D:\Android\android-sdk\platform-tools\adb.exe',
    [string]$Target = '192.168.3.213:5555',
    [switch]$Verify
)

$ErrorActionPreference = 'Continue'

if (-not (Test-Path $Adb)) { throw "找不到 adb：$Adb" }

function Invoke-OnDevice([string]$command) {
    return (& $Adb -s $Target shell $command 2>&1)
}

# 连接（无线 adb 需要先 connect；已连接时这条是幂等的）
& $Adb connect $Target 2>&1 | Out-Null
$state = (& $Adb devices 2>&1) | Select-String $Target
if (-not $state) { throw "设备未连接：$Target（先 adb connect）" }

$names = @{ 0 = '熄屏即关 Wi-Fi'; 1 = '熄屏关 Wi-Fi（充电时保持）'; 2 = '永不休眠 ✔' }

$current = (Invoke-OnDevice "settings get global wifi_sleep_policy") -join ''
$current = $current.Trim()
Write-Host "当前策略：$current  ($($names[[int]$current]))" -ForegroundColor Cyan

if ($Verify) {
    Write-Host (Invoke-OnDevice "dumpsys wifi | grep -iE 'Wi-Fi is|Amount of time wifi is in sleep'" | Out-String)
    Write-Host "提示：想看"息屏是否真的不断"，可对比两次计时器增量：" -ForegroundColor DarkGray
    Write-Host "  adb shell dumpsys wifi | grep 'Amount of time wifi is in sleep'" -ForegroundColor DarkGray
    return
}

Write-Host "写入 wifi_sleep_policy=$Policy ..." -ForegroundColor Yellow
$null = Invoke-OnDevice "settings put global wifi_sleep_policy $Policy"

$after = (Invoke-OnDevice "settings get global wifi_sleep_policy") -join ''
$after = $after.Trim()
if ($after -eq "$Policy") {
    Write-Host "已生效：$after  ($($names[$Policy]))" -ForegroundColor Green
    Write-Host ""
    Write-Host "验证方法（屏幕关闭状态下对比休眠计时器增量）：" -ForegroundColor DarkGray
    Write-Host "  1) input keyevent KEYCODE_SLEEP" -ForegroundColor DarkGray
    Write-Host "  2) dumpsys wifi | grep 'Amount of time wifi is in sleep'   # 记下数字" -ForegroundColor DarkGray
    Write-Host "  3) 等 45 秒后再取一次：数字不变 = Wi-Fi 没有休眠" -ForegroundColor DarkGray
} else {
    Write-Warning "写入未生效（当前 $after）。可能是 adb shell 缺少 WRITE_SECURE_SETTINGS 权限。"
    Write-Host "备选：用 App 内 WifiLock 保持（需在 App 里申请并持有），或换用有权限的 adb。" -ForegroundColor Yellow
}
