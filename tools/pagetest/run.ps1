# 在桌面上运行分页无缝性测试（不需要手表、不需要模拟器）
#
#   & .\tools\pagetest\run.ps1
#
# 原理：只把 Paginator / PageMap / Book 的**纯逻辑**拿真实 JDK 跑起来，
#      android.text.Paint / TextPaint 用 tools\pagetest\android\text 下的最小 stub 顶替
#      （分页不变量只依赖"测量函数"，与 Android 运行时无关）。
#
# 编译产物放在临时目录，不污染仓库。
param(
    [string]$JdkHome = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # 项目根
. (Join-Path $root 'tools\env.ps1')

if (-not $JdkHome) { $JdkHome = Get-WrEnv 'JdkHome' }
try { Assert-WrJdk $JdkHome | Out-Null } catch { Write-Host $_.Exception.Message -ForegroundColor Red; return }

$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
$srcDir = Join-Path $root 'android\app\java'
$stubDir = $PSScriptRoot
$out = Join-Path $env:TEMP 'wr-pagetest-out'

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null

Write-Host "==> 1/2 编译（stub + Book + Paginator + PageMap + 测试）" -ForegroundColor Cyan
$sources = @(
    (Join-Path $stubDir 'android\graphics\Paint.java'),
    (Join-Path $stubDir 'android\text\TextPaint.java'),
    (Join-Path $stubDir 'android\util\Log.java'),
    (Join-Path $srcDir 'com\watchreader\TextDecoder.java'),
    (Join-Path $srcDir 'com\watchreader\Book.java'),
    (Join-Path $srcDir 'com\watchreader\Paginator.java'),
    (Join-Path $srcDir 'com\watchreader\PageMap.java'),
    (Join-Path $srcDir 'com\watchreader\PageIndexCache.java'),
    (Join-Path $stubDir 'java\com\watchreader\PaginateCheck.java'),
    (Join-Path $stubDir 'java\com\watchreader\BoundaryCheck.java'),
    (Join-Path $stubDir 'java\com\watchreader\BookCheck.java')
)
& $javac -encoding UTF-8 -nowarn -d $out $sources
if ($LASTEXITCODE -ne 0) { Write-Host "编译失败" -ForegroundColor Red; return }

Write-Host "==> 2/2 运行（PaginateCheck + BoundaryCheck + BookCheck）" -ForegroundColor Cyan
$code = 0
foreach ($cls in @('PaginateCheck', 'BoundaryCheck', 'BookCheck')) {
    Write-Host ""
    Write-Host "---- $cls ----" -ForegroundColor DarkGray
    & $java "-Dfile.encoding=UTF-8" -Xmx1g -cp $out "com.watchreader.$cls"
    if ($LASTEXITCODE -ne 0) { $code = 1 }
}

Write-Host ""
if ($code -eq 0) {
    Write-Host "分页/书库测试全部通过 ✓" -ForegroundColor Green
} else {
    Write-Host "有测试失败 ✗" -ForegroundColor Red
}
exit $code
