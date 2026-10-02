# 自动验证"息屏保持 Wi-Fi"开关：唤醒 → 进阅读页 → 开菜单 → 点开关 → 校验锁与状态
# 依赖：tools\probe-screen.ps1 用的同一套几何推算
param(
    [string]$Adb = 'D:\Android\android-sdk\platform-tools\adb.exe',
    [string]$Target = '192.168.3.213:5555',
    [int]$WifiRowIndex = 3
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Drawing

function OnDevice([string]$c) { return (& $Adb -s $Target shell $c 2>&1) }
function Wake() {
    OnDevice "input keyevent KEYCODE_WAKEUP" | Out-Null
    Start-Sleep -Milliseconds 800
}
function Focus2() { return ((OnDevice "dumpsys window | grep mCurrentFocus") | Select-Object -Last 1) }
function PanelTop() {
    $shot = Join-Path $env:TEMP 'probe2.png'
    & $Adb -s $Target shell "screencap -p /sdcard/probe2.png" 2>&1 | Out-Null
    & $Adb -s $Target pull /sdcard/probe2.png $shot 2>&1 | Out-Null
    if (-not (Test-Path $shot)) { return $null }
    $bmp = New-Object System.Drawing.Bitmap($shot)
    $top = -1; $bot = -1
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        $p = $bmp.GetPixel(60, $y)
        if ([math]::Abs([int]$p.R - 22) -lt 4 -and [math]::Abs([int]$p.G - 28) -lt 4 -and [math]::Abs([int]$p.B - 37) -lt 4) {
            if ($top -lt 0) { $top = $y }
            $bot = $y
        }
    }
    $bmp.Dispose()
    if ($top -lt 0) { return $null }
    return @{ top = $top; bottom = $bot }
}

& $Adb connect $Target 2>&1 | Out-Null

Write-Host "1) 确保屏幕点亮" -ForegroundColor Cyan
Wake
Write-Host "   屏幕：$((OnDevice "dumpsys power | grep -E 'mWakefulness='") | Select-Object -First 1)"

Write-Host "2) 回到表盘再启动 App（保证从书库开始）" -ForegroundColor Cyan
OnDevice "input keyevent KEYCODE_HOME" | Out-Null
Start-Sleep -Seconds 1
OnDevice "am force-stop com.watchreader" | Out-Null
OnDevice "am start -n com.watchreader/.MainActivity" | Out-Null
Start-Sleep -Seconds 5
Wake
Write-Host "   前台：$(Focus2)"

Write-Host "3) 打开书库第一本书" -ForegroundColor Cyan
OnDevice "input tap 200 155" | Out-Null
Start-Sleep -Seconds 10
$f = Focus2
Write-Host "   前台：$f"
if ($f -notmatch 'ReaderActivity') { Write-Host "   !! 没进阅读页，中止" -ForegroundColor Red; return }

Write-Host "4) 点底部中央打开设置菜单" -ForegroundColor Cyan
OnDevice "input tap 233 440" | Out-Null
Start-Sleep -Seconds 3
$p = PanelTop
if ($null -eq $p) { Write-Host "   !! 菜单未打开，中止" -ForegroundColor Red; return }
Write-Host "   面板 y $($p.top)..$($p.bottom)" -ForegroundColor Green

$rowH = [math]::Max(30, [math]::Min(44, 466 * 0.078))
$titleH = [math]::Max(24, $rowH * 0.75)
$listTop = $p.top + $titleH
$clickY = [int]($listTop + $WifiRowIndex * $rowH + $rowH / 2)
Write-Host "5) 点第 $WifiRowIndex 项，y=$clickY" -ForegroundColor Cyan
OnDevice "input tap 233 $clickY" | Out-Null
Start-Sleep -Seconds 3

Write-Host "=== 结果 ===" -ForegroundColor Cyan
Write-Host "菜单触摸诊断："
(OnDevice "run-as com.watchreader cat files/crown-trace.log 2>/dev/null") | Select-String 'menuTap' | Select-Object -Last 3 | ForEach-Object { Write-Host "  $_" }
Write-Host "开关与锁："
(OnDevice "run-as com.watchreader cat shared_prefs/watchreader.xml 2>/dev/null") | Select-String 'keepWifi' | ForEach-Object { Write-Host "  $_" }
(OnDevice "logcat -d -v brief | grep -E 'watchreader.*(已持有|已释放)'") | Select-Object -Last 3 | ForEach-Object { Write-Host "  $_" }
Write-Host "系统统计的 high-perf 锁活跃时长："
(OnDevice "dumpsys wifi | grep -E 'high_perf_active_time_ms'") | ForEach-Object { Write-Host "  $_" }
