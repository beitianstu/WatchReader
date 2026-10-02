# 读取手表当前屏幕状态 + 测量菜单面板几何（防御式，不会因为未找到面板而刷屏）
# 用法： & .\tools\probe-screen.ps1
param(
    [string]$Adb = '',
    [string]$Target = '',
    [switch]$NoShot
)

$ErrorActionPreference = 'Continue'

. (Join-Path $PSScriptRoot 'env.ps1')

if (-not $Adb) { $Adb = Get-WrEnv 'Adb' }
if (-not $Target) { $Target = Get-WrEnv 'Target' }
try { Assert-WrAdb $Adb | Out-Null } catch { Write-Host $_.Exception.Message -ForegroundColor Red; return }
Add-Type -AssemblyName System.Drawing

function OnDevice([string]$c) { return (& $Adb -s $Target shell $c 2>&1) }

Write-Host "=== 连接 ===" -ForegroundColor Cyan
& $Adb connect $Target 2>&1 | Out-Null
$dev = (& $Adb devices 2>&1) | Select-String $Target
if (-not $dev) { Write-Host "设备未连接" -ForegroundColor Red; return }
Write-Host ($dev -join '')

Write-Host "=== 前台窗口 ===" -ForegroundColor Cyan
(OnDevice "dumpsys window | grep mCurrentFocus") | Select-Object -First 2 | ForEach-Object { Write-Host "  $_" }
Write-Host "=== 屏幕状态 ===" -ForegroundColor Cyan
(OnDevice "dumpsys power | grep -E 'mWakefulness='") | Select-Object -First 1 | ForEach-Object { Write-Host "  $_" }

if ($NoShot) { return }

$shot = Join-Path $env:TEMP 'probe-screen.png'
& $Adb -s $Target shell "screencap -p /sdcard/probe.png" 2>&1 | Out-Null
& $Adb -s $Target pull /sdcard/probe.png $shot 2>&1 | Out-Null
if (-not (Test-Path $shot)) { Write-Host "截图失败" -ForegroundColor Red; return }

$bmp = New-Object System.Drawing.Bitmap($shot)
$w = $bmp.Width; $h = $bmp.Height
Write-Host "=== 图像 $w x $h ===" -ForegroundColor Cyan

function IsPanel($p) {
    return ([math]::Abs([int]$p.R - 22) -lt 4 -and [math]::Abs([int]$p.G - 28) -lt 4 -and [math]::Abs([int]$p.B - 37) -lt 4)
}

# 用 x=60 这一列纵向扫描：面板存在时会有一段连续的 #161C25
$top = -1; $bottom = -1
for ($y = 0; $y -lt $h; $y++) {
    if (IsPanel $bmp.GetPixel(60, $y)) { if ($top -lt 0) { $top = $y }; $bottom = $y }
}

if ($top -lt 0) {
    Write-Host "未检测到菜单面板（x=60 列无 #161C25）" -ForegroundColor Yellow
    # 顺便给出画面亮度分布，帮助判断当前在哪一屏
    $bright = 0
    for ($y = 40; $y -lt ($h - 40); $y += 4) {
        for ($x = 40; $x -lt ($w - 40); $x += 4) {
            $p = $bmp.GetPixel($x, $y)
            if ((0.299 * $p.R + 0.587 * $p.G + 0.114 * $p.B) -gt 110) { $bright++ }
        }
    }
    Write-Host "  画面亮像素采样数：$bright（正文页通常几千，菜单页更多）"
    $bmp.Dispose()
    return
}

$midY = [int](($top + $bottom) / 2)
$left = -1; $right = -1
for ($x = 0; $x -lt $w; $x++) { if (IsPanel $bmp.GetPixel($x, $midY)) { $left = $x; break } }
for ($x = $w - 1; $x -ge 0; $x--) { if (IsPanel $bmp.GetPixel($x, $midY)) { $right = $x; break } }

Write-Host ("面板：x {0}..{1}（宽 {2} = {3:N1}% 屏宽）  y {4}..{5}（高 {6}）" -f `
    $left, $right, ($right - $left), (($right - $left) / $w * 100), $top, $bottom, ($bottom - $top)) -ForegroundColor Green

# 按 app 里的同一套公式推算行位，方便脚本精确点击
$rowH = [math]::Max(30, [math]::Min(44, $h * 0.078))
$titleH = [math]::Max(24, $rowH * 0.75)
$footerH = [math]::Max(32, [math]::Min(46, $h * 0.10))
$listTop = $top + $titleH
$listBottom = $bottom - $footerH
Write-Host ("推算：rowH={0:N1} titleH={1:N1} footerH={2:N1} 列表 y {3:N1}..{4:N1}" -f `
    $rowH, $titleH, $footerH, $listTop, $listBottom)

$visible = [math]::Floor(($listBottom - $listTop) / $rowH)
Write-Host "可见行数：$visible" -ForegroundColor Cyan
for ($i = 0; $i -lt $visible; $i++) {
    $clickY = [int]($listTop + $i * $rowH + $rowH / 2)
    Write-Host ("  第 {0} 项 → 点击 y={1}" -f $i, $clickY)
}
$btnY = [int]($bottom - $footerH / 2)
Write-Host ("确认按钮 → 点击 y={0}" -f $btnY) -ForegroundColor Cyan

$bmp.Dispose()
