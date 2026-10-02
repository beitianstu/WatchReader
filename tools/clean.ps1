# 清理可再生的构建产物 / 临时文件
#
#   & .\tools\clean.ps1                 # 预览（只列出会删什么，不实际删）
#   & .\tools\clean.ps1 -Apply          # 真正删除
#   & .\tools\clean.ps1 -Apply -Deep    # 连 APK/classes/dex 一起删（下次构建会重新生成）
#
# 默认**不动**这些：
#   · android\build\debug.keystore  —— 签名密钥，删了会导致覆盖安装因签名不一致失败
#   · 所有源码、资源、文档、tools 脚本
param(
    [switch]$Apply,
    [switch]$Deep
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

# (相对路径, 说明)
$targets = New-Object System.Collections.ArrayList
function Add-Target([string]$rel, [string]$note) {
    [void]$targets.Add([pscustomobject]@{ rel = $rel; note = $note })
}

# 安卓调试遗留（截图/日志/一次性的 dump）
foreach ($f in @('crown-trace.log', 'crown-trace2.txt', 'page-diag.log', 'page-diag2.txt',
                 'trace3.txt', 'ui.xml', 'ui2.xml', 'ui3.xml', 'ui4.xml', 'novel.txt')) {
    Add-Target "android\build\$f" '调试遗留'
}

if ($Deep) {
    Add-Target 'android\build\apk' 'APK 产物（可重建）'
    Add-Target 'android\build\classes' 'javac 输出（可重建）'
    Add-Target 'android\build\dex' 'd8 输出（可重建）'
    Add-Target 'android\build\gen' 'aapt2 生成的 R.java（可重建）'
}

Write-Host "项目根：$root" -ForegroundColor Cyan
Write-Host $(if ($Apply) { '模式：实际删除' } else { '模式：预览（加 -Apply 才会真删）' }) -ForegroundColor Yellow
Write-Host ""

$totalBytes = 0
$hit = 0
foreach ($t in $targets) {
    $full = Join-Path $root $t.rel
    # 安全校验：解析后的绝对路径必须仍在项目内
    $resolved = [System.IO.Path]::GetFullPath($full)
    if (-not $resolved.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        Write-Warning "跳过（越界）：$resolved"
        continue
    }
    if (-not (Test-Path $full)) { continue }

    $item = Get-Item $full -Force
    $size = if ($item.PSIsContainer) {
        (Get-ChildItem $full -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    } else { $item.Length }
    if (-not $size) { $size = 0 }

    $totalBytes += $size
    $hit++
    if ($Apply) {
        Remove-Item $full -Recurse -Force
        Write-Host ("  已删  {0,12:N0} B  {1}" -f $size, $t.rel) -ForegroundColor Green
    } else {
        Write-Host ("  将删  {0,12:N0} B  {1}  ({2})" -f $size, $t.rel, $t.note)
    }
}

Write-Host ""
if ($hit -eq 0) {
    Write-Host "没有需要清理的内容。" -ForegroundColor Green
} else {
    Write-Host ("{0} 项，共 {1:N2} MB" -f $hit, ($totalBytes / 1MB)) -ForegroundColor Cyan
    if (-not $Apply) { Write-Host "确认无误后执行： & .\tools\clean.ps1 -Apply" -ForegroundColor Yellow }
}

# 始终确认密钥安全
$ks = Join-Path $root 'android\build\debug.keystore'
if (Test-Path $ks) {
    Write-Host "签名密钥已保留：android\build\debug.keystore" -ForegroundColor Green
} else {
    Write-Warning "签名密钥不存在！下次构建会重新生成，届时覆盖安装会因签名不一致失败。"
}
