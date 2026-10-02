# 阅读进度持久化回归测试
#   打开书 → 记录进度 → 翻几页 → 强杀进程 → 重启重开 → 验证进度是否恢复
#
# 用法：
#   & .\tools\test-progress.ps1
#   & .\tools\test-progress.ps1 -BookTapY 155 -Turns 3
#
# 判据：
#   · 翻页后 offset 应增大（至少翻了页）
#   · 强杀重启后重开，offset 应等于"翻页后"的值（说明已落盘且能读回）
param(
    [string]$Adb = '',
    [string]$Target = '',
    [int]$BookTapY = 155,     # 书库第一本书行的点击 y
    [int]$Turns = 3,
    [int]$FlipX = 380,        # 点屏幕右侧翻页
    [int]$FlipY = 233
)

$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'env.ps1')

if (-not $Adb) { $Adb = Get-WrEnv 'Adb' }
if (-not $Target) { $Target = Get-WrEnv 'Target' }
try { Assert-WrAdb $Adb | Out-Null } catch { Write-Host $_.Exception.Message -ForegroundColor Red; return }

function OnDevice([string]$c) { return (& $Adb -s $Target shell $c 2>&1 | Out-String) }
function Get-Prefs { return (OnDevice 'run-as com.watchreader cat shared_prefs/watchreader.xml') }
function Wake { OnDevice 'input keyevent KEYCODE_WAKEUP' | Out-Null; Start-Sleep -Milliseconds 700 }
function Focus { $o = OnDevice 'dumpsys window | grep mCurrentFocus'; $lines = @($o -split "`r?`n" | Where-Object { $_.Trim() }); if ($lines.Count -gt 0) { return $lines[-1].Trim() } else { return '(无输出)' } }

# 当前书架第一本的进度（offset|page|percent）
function Get-FirstBookProgress {
    $xml = Get-Prefs
    # lastOpen 记的是最后打开的书，用它确定"第一本"对应的 cacheId 不方便；
    # 改为：列出所有 progress.* 记录，返回字典，便于对比前后变化
    $map = @{}
    foreach ($line in ($xml -split "`n")) {
        if ($line -match '<string name="progress\.([0-9a-f]+)">([^<]*)</string>') {
            $map[$Matches[1]] = $Matches[2]
        }
    }
    return $map
}

function Show-Progress([hashtable]$map, [string]$title) {
    Write-Host "  $title" -ForegroundColor Cyan
    foreach ($k in ($map.Keys | Sort-Object)) {
        Write-Host ("    {0} = {1}" -f $k, $map[$k])
    }
}

Write-Host "=== 0) 环境确认 ===" -ForegroundColor Cyan
Wake
Write-Host "  屏幕: $((OnDevice 'dumpsys power | grep -m1 mWakefulness=') -replace '\s+','')"

Write-Host "=== 1) 启动 App（从书库开始） ===" -ForegroundColor Cyan
OnDevice 'input keyevent KEYCODE_HOME' | Out-Null
Start-Sleep -Seconds 1
OnDevice 'am force-stop com.watchreader' | Out-Null
OnDevice 'am start -n com.watchreader/.MainActivity' | Out-Null
Start-Sleep -Seconds 5
Wake
Write-Host "  前台: $(Focus)"

$before = Get-FirstBookProgress
Show-Progress $before "打开前的进度记录"

Write-Host "=== 2) 打开第一本书 ===" -ForegroundColor Cyan
OnDevice "input tap 200 $BookTapY" | Out-Null
Start-Sleep -Seconds 10
$f = Focus
Write-Host "  前台: $f"
if ($f -notmatch 'ReaderActivity') { Write-Host "  !! 没进阅读页，中止" -ForegroundColor Red; return }
Write-Host "  lastOpen: $((Get-Prefs) -split "`n" | Where-Object { $_ -match 'lastOpen' } | ForEach-Object { $_.Trim() })"

$afterOpen = Get-FirstBookProgress
Show-Progress $afterOpen "打开后的进度记录"

Write-Host "=== 3) 翻 $Turns 页 ===" -ForegroundColor Cyan
for ($i = 1; $i -le $Turns; $i++) {
    OnDevice "input tap $FlipX $FlipY" | Out-Null
    Start-Sleep -Milliseconds 600
}
Start-Sleep -Seconds 2
$afterTurns = Get-FirstBookProgress
Show-Progress $afterTurns "翻页后的进度记录"

Write-Host "=== 4) 强杀进程（模拟系统回收） ===" -ForegroundColor Cyan
OnDevice 'am force-stop com.watchreader' | Out-Null
Start-Sleep -Seconds 3
$afterKill = Get-FirstBookProgress
Show-Progress $afterKill "强杀后（应等于翻页后的值 = 已落盘）"

Write-Host "=== 5) 重启并重开同一本书 ===" -ForegroundColor Cyan
OnDevice 'am start -n com.watchreader/.MainActivity' | Out-Null
Start-Sleep -Seconds 5
Wake
OnDevice "input tap 200 $BookTapY" | Out-Null
Start-Sleep -Seconds 10
Write-Host "  前台: $(Focus)"
$afterReopen = Get-FirstBookProgress
Show-Progress $afterReopen "重开后的进度记录"

Write-Host "=== 结论 ===" -ForegroundColor Cyan
# 找出"翻页后"与"重开后"的差异
$keys = $afterTurns.Keys + $afterReopen.Keys | Sort-Object -Unique
foreach ($k in $keys) {
    $t = $afterTurns[$k]; $r = $afterReopen[$k]
    $to = if ($t) { [int](($t -split '\|')[0]) } else { -1 }
    $ro = if ($r) { [int](($r -split '\|')[0]) } else { -1 }
    $mark = if ($to -eq $ro) { '✓ 一致' } else { "✗ 不一致（差 $($ro - $to)）" }
    Write-Host ("  {0}: 翻页后 offset={1}  重开后 offset={2}  {3}" -f $k, $to, $ro, $mark)
}
Write-Host ""
Write-Host "说明：重开后 offset 与翻页后一致 = 进度正确保存并恢复；" -ForegroundColor DarkGray
Write-Host "      若重开后 offset 回到旧值或 0，说明落盘/读取有问题。" -ForegroundColor DarkGray
