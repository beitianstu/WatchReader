# 打开书籍加载性能测试（桌面跑，不需要手表 / 不需要模拟器）
#
#   & .\tools\loadtest\run.ps1                 # 默认合成 2MB 中文大书
#   & .\tools\loadtest\run.ps1 -Megabytes 4    # 改合成书大小
#   & .\tools\loadtest\run.ps1 -KeepData       # 保留合成数据目录，便于手工复现
#
# 原理：把 Book / Paginator / PageMap / TextDecoder 的纯逻辑用真实 JDK 跑起来，
#      android.graphics.Paint / android.text.TextPaint / android.util.Log 用
#      tools\pagetest 下的最小 stub 顶替（分页不变量只依赖"测量函数"）。
#      合成数据只写在临时目录，不读也不动任何真实书籍。
param(
    [string]$JdkHome = '',
    [int]$Megabytes = 2,
    [switch]$KeepData
)

$ErrorActionPreference = 'Stop'
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent   # 项目根
. (Join-Path $root 'tools\env.ps1')

if (-not $JdkHome) { $JdkHome = Get-WrEnv 'JdkHome' }
try { Assert-WrJdk $JdkHome | Out-Null } catch { Write-Host $_.Exception.Message -ForegroundColor Red; return }

$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
$srcDir = Join-Path $root 'android\app\java'
$stubDir = Join-Path $root 'tools\pagetest'
$out = Join-Path $env:TEMP 'wr-loadtest-out'
$data = Join-Path $env:TEMP 'wr-loadtest-data'

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force -Path $out | Out-Null
if (-not $KeepData -and (Test-Path $data)) { Remove-Item $data -Recurse -Force }

Write-Host "==> 1/2 编译（stub + 生产纯逻辑 + 性能检查）" -ForegroundColor Cyan
$sources = @(
    (Join-Path $stubDir 'android\graphics\Paint.java'),
    (Join-Path $stubDir 'android\text\TextPaint.java'),
    (Join-Path $stubDir 'android\util\Log.java'),
    (Join-Path $srcDir 'com\watchreader\TextDecoder.java'),
    (Join-Path $srcDir 'com\watchreader\Book.java'),
    (Join-Path $srcDir 'com\watchreader\BookCache.java'),
    (Join-Path $srcDir 'com\watchreader\Paginator.java'),
    (Join-Path $srcDir 'com\watchreader\PageMap.java'),
    (Join-Path $srcDir 'com\watchreader\PageIndexCache.java')
)
# 章节识别如果被抽成独立类，也一起编译（性能检查会测它）
$chapters = Join-Path $srcDir 'com\watchreader\Chapters.java'
if (Test-Path $chapters) { $sources += $chapters }
$sources += (Join-Path $PSScriptRoot 'java\com\watchreader\LoadPerfCheck.java')

& $javac -encoding UTF-8 -nowarn -d $out $sources
if ($LASTEXITCODE -ne 0) { Write-Host "编译失败" -ForegroundColor Red; exit 1 }

Write-Host "==> 2/2 运行 LoadPerfCheck（合成 ${Megabytes}MB 中文书）" -ForegroundColor Cyan
Write-Host ""
& $java "-Dfile.encoding=UTF-8" -Xmx1g -cp $out "com.watchreader.LoadPerfCheck" $Megabytes $data
$code = $LASTEXITCODE
Write-Host ""
if ($code -eq 0) {
    Write-Host "加载性能检查通过 ✓" -ForegroundColor Green
} else {
    Write-Host "加载性能检查失败 ✗" -ForegroundColor Red
}
exit $code
