# 构建 WatchReader 安卓 APK（不依赖 Gradle：aapt2 → javac → d8 → zipalign → apksigner）
#
# 用法：
#   & .\tools\build-apk.ps1                # Debug 构建（debuggable，可用 run-as 调试）
#   & .\tools\build-apk.ps1 -Install       # 构建完成后 adb install -r
#   & .\tools\build-apk.ps1 -Release       # 正式包（不带 debuggable）
#
# 注意：华为 Watch3/4 系列默认禁止 ADB 安装第三方应用，
#      装机前必须先冻结应用安装器（详见 tools\install-apk.ps1）。
#
# SDK / JDK / adb 的位置由 tools\env.ps1 自动解析
# （环境变量 ANDROID_HOME / JAVA_HOME → 常见安装位置 → 报错提示），
# 也可以用下面的参数显式指定。
param(
    [string]$Sdk = '',
    [string]$JdkHome = '',
    [string]$CompileSdk = 'android-33',
    [int]$MinSdk = 29,
    [int]$TargetSdk = 29,
    [string]$Adb = '',
    [switch]$Install,
    [switch]$Release
)

. (Join-Path $PSScriptRoot 'env.ps1')

# 外部工具（keytool/javac/d8）会把正常提示写到 stderr，
# 在 PS 5.1 下会被当成 error 并触发 Stop 而中断，所以只用 Continue，逐步检查 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'

# 解析并校验外部依赖（参数优先，其次环境变量/探测）
if (-not $Sdk) { $Sdk = Get-WrEnv 'Sdk' }
if (-not $JdkHome) { $JdkHome = Get-WrEnv 'JdkHome' }
if (-not $Adb) { $Adb = Get-WrEnv 'Adb' }
try {
    Assert-WrSdk $Sdk | Out-Null
    Assert-WrJdk $JdkHome | Out-Null
    Assert-WrAdb $Adb | Out-Null
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    return
}

$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\android'))
$appDir = Join-Path $projectRoot 'app'
$buildDir = Join-Path $projectRoot 'build'
$genDir = Join-Path $buildDir 'gen'
$classesDir = Join-Path $buildDir 'classes'
$dexDir = Join-Path $buildDir 'dex'
$apkDir = Join-Path $buildDir 'apk'
$keystore = Join-Path $buildDir 'debug.keystore'

$buildTools = Join-Path $Sdk 'build-tools\34.0.0'
$aapt2 = Join-Path $buildTools 'aapt2.exe'
$d8 = Join-Path $buildTools 'd8.bat'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$androidJar = Join-Path $Sdk "platforms\$CompileSdk\android.jar"
$javac = Join-Path $JdkHome 'bin\javac.exe'
$keytool = Join-Path $JdkHome 'bin\keytool.exe'

foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $androidJar, $javac, $keytool)) {
    if (-not (Test-Path $tool)) { throw "缺少构建工具：$tool" }
}

function Step([string]$name) {
    Write-Host ""
    Write-Host "==> $name" -ForegroundColor Cyan
}

# 清理
foreach ($d in @($genDir, $classesDir, $dexDir, $apkDir)) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# 签名库（不存在则生成，密码固定 android，仅用于本机调试）
if (-not (Test-Path $keystore)) {
    Step "生成调试签名库"
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android `
        -alias watchreader -keyalg RSA -keysize 2048 -validity 10950 `
        -dname "CN=WatchReader, OU=Dev, O=WatchReader, L=CN, S=CN, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool 生成签名库失败" }
}

$sourceFiles = Get-ChildItem -Path (Join-Path $appDir 'java') -Filter '*.java' -Recurse |
    Select-Object -ExpandProperty FullName
if ($sourceFiles.Count -eq 0) { throw "没有找到任何 .java 源文件" }

# 1) 编译资源
Step "aapt2 compile 资源"
$resDir = Join-Path $appDir 'res'
$compiledRes = Join-Path $buildDir 'res.zip'
& $aapt2 compile --dir $resDir -o $compiledRes
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

# 2) 链接资源 + 生成 R.java
Step "aapt2 link（生成 R.java 与基础 APK）"
$manifest = Join-Path $appDir 'AndroidManifest.xml'
$baseApk = Join-Path $apkDir 'base.apk'
$linkArgs = @(
    'link',
    '-o', $baseApk,
    '-I', $androidJar,
    '--manifest', $manifest,
    '--java', $genDir,
    '--min-sdk-version', "$MinSdk",
    '--target-sdk-version', "$TargetSdk",
    '--version-code', '1',
    '--version-name', '1.0'
)
# debuggable 只能写在 AndroidManifest 里（aapt2 link 没有这个选项）
$linkArgs += $compiledRes
& $aapt2 @linkArgs
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

# 3) 编译 Java
Step "javac 编译 Java 源码"
$rJava = Get-ChildItem -Path $genDir -Filter 'R.java' -Recurse | Select-Object -ExpandProperty FullName
$allSources = @($sourceFiles) + @($rJava)
& $javac -encoding UTF-8 --release 11 -nowarn `
    -classpath $androidJar `
    -d $classesDir $allSources 2>&1 | ForEach-Object { Write-Host "    $_" }
if ($LASTEXITCODE -ne 0) { throw "javac 编译失败" }

# 4) dex
Step "d8 生成 dex"
$classFiles = Get-ChildItem -Path $classesDir -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName
& $d8 --min-api $MinSdk --output $dexDir --lib $androidJar @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

# 5) 把 dex 打进 APK
Step "打包 classes.dex"
$zip = Join-Path $apkDir 'unsigned.apk'
Copy-Item $baseApk $zip -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::Open($zip, 'Update')
try {
    $entryName = 'classes.dex'
    $existing = $archive.GetEntry($entryName)
    if ($existing) { $existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $archive, (Join-Path $dexDir 'classes.dex'), $entryName) | Out-Null
} finally {
    $archive.Dispose()
}

# 6) 对齐
Step "zipalign 4 字节对齐"
$alignedApk = Join-Path $apkDir 'aligned.apk'
& $zipalign -f -p 4 $zip $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

# 7) 签名（V1+V2）
Step "apksigner 签名"
$finalApk = Join-Path $apkDir 'WatchReader.apk'
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias watchreader --v1-signing-enabled true --v2-signing-enabled true `
    --out $finalApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

Step "校验签名"
& $apksigner verify --print-certs $finalApk 2>&1 | Select-String -Pattern 'SHA-256|Verified using'

$size = (Get-Item $finalApk).Length
Write-Host ""
Write-Host ("APK 生成成功： {0}  ({1:N0} 字节)" -f $finalApk, $size) -ForegroundColor Green

if ($Install) {
    Step "安装到设备（自动冻结/解冻应用安装器）"
    & (Join-Path $PSScriptRoot 'install-apk.ps1') -Apk $finalApk -Adb $Adb
}
