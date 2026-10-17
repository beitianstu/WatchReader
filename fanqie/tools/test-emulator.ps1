param([string]$Serial='emulator-5554',[switch]$PhysicalWatch,[switch]$ProgressOnly,[switch]$LibraryOnly,[switch]$HostPlugin,[switch]$CrownOnly,[switch]$ParallelOnly,[switch]$NativeParallelOnly)
$ErrorActionPreference='Stop'
if(-not $HostPlugin -and -not $NativeParallelOnly){throw '0.2.0 uses the host/plugin integration runner. Add -HostPlugin; old standalone tests remain historical.'}
if($Serial -notmatch '^emulator-\d+$' -and -not($PhysicalWatch -and -not [string]::IsNullOrWhiteSpace($Serial))){throw 'Use an emulator or the explicitly authorized watch session.'}
$moduleRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot=Split-Path $moduleRoot -Parent
. (Join-Path $repoRoot 'tools\env.ps1')
$sdk=Get-WrEnv 'Sdk';$jdk=Get-WrEnv 'JdkHome';$adb=Get-WrEnv 'Adb'
$env:JAVA_HOME=$jdk;$env:PATH=(Join-Path $jdk 'bin')+';'+$env:PATH
$latest='build\latest-build.txt';if($HostPlugin -and -not $NativeParallelOnly){$latest='build\latest-host-build.txt'}
$appBuild=(Get-Content (Join-Path $moduleRoot $latest) -Raw).Trim()
$build=Join-Path $moduleRoot ('build\tests-'+[guid]::NewGuid().ToString('N'))
@($build,(Join-Path $build 'classes'),(Join-Path $build 'dex'))|ForEach-Object{New-Item -ItemType Directory -Force -Path $_|Out-Null}
$bt=Join-Path $sdk 'build-tools\34.0.0';$jar=Join-Path $sdk 'platforms\android-33\android.jar'
function Run-Checked([string]$exe,[string[]]$arguments){$ErrorActionPreference='Continue';& $exe @arguments 2>&1|ForEach-Object{Write-Host $_};if($LASTEXITCODE -ne 0){throw "Test build failed: $exe"}}
$testRoot=Join-Path $moduleRoot 'tests';if($HostPlugin){$testRoot=Join-Path $moduleRoot 'integration'}
if($NativeParallelOnly){$testRoot=Join-Path $moduleRoot 'plugin-tests'}
Run-Checked (Join-Path $bt 'aapt2.exe') @('link','-o',(Join-Path $build 'test.apk'),'-I',$jar,'--manifest',(Join-Path $testRoot 'AndroidManifest.xml'))
$sources=@(Get-ChildItem (Join-Path $testRoot 'java') -Filter '*.java' -Recurse|Select-Object -ExpandProperty FullName)
Run-Checked (Join-Path $jdk 'bin\javac.exe') (@('-encoding','UTF-8','--release','11','-classpath',($jar+';'+(Join-Path $appBuild 'classes')),'-d',(Join-Path $build 'classes'))+$sources)
$classes=@(Get-ChildItem (Join-Path $build 'classes') -Filter '*.class' -Recurse|Select-Object -ExpandProperty FullName)
Run-Checked (Join-Path $bt 'd8.bat') (@('--min-api','29','--output',(Join-Path $build 'dex'),'--lib',$jar,'--classpath',(Join-Path $appBuild 'classes'))+$classes)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::Open((Join-Path $build 'test.apk'),'Update')
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $build 'dex\classes.dex'),'classes.dex')|Out-Null}finally{$archive.Dispose()}
Run-Checked (Join-Path $bt 'zipalign.exe') @('-f','4',(Join-Path $build 'test.apk'),(Join-Path $build 'aligned.apk'))
$testKey=Join-Path $moduleRoot 'build\local-signing.keystore';$testKeyPass='file:'+(Join-Path $moduleRoot 'build\local-signing.password');$alias='fanqie-watch'
if($HostPlugin -and -not $NativeParallelOnly){$testKey=Join-Path $repoRoot 'android\build\debug.keystore';$testKeyPass='pass:android';$alias='watchreader'}
Run-Checked (Join-Path $bt 'apksigner.bat') @('sign','--ks',$testKey,'--ks-pass',$testKeyPass,'--ks-key-alias',$alias,'--out',(Join-Path $build 'signed.apk'),(Join-Path $build 'aligned.apk'))
if($PhysicalWatch){& (Join-Path $PSScriptRoot 'install-watch-apk.ps1') -Serial $Serial -Apk (Join-Path $build 'signed.apk')}
else{Run-Checked $adb @('-s',$Serial,'install','--no-incremental','-r',(Join-Path $build 'signed.apk'))}
$target='com.watchreader.fanqie';$testPackage='com.watchreader.fanqie.tests';$reportFile='files/test-report.json';if($HostPlugin -and -not $NativeParallelOnly){$target='com.watchreader';$testPackage='com.watchreader.integration.tests';$reportFile='files/host-plugin-report.json'}
if($ParallelOnly){$reportFile='files/parallel-download-report.json'}
if($NativeParallelOnly){$target='com.watchreader.fanqie';$testPackage='com.watchreader.plugin.tests';$reportFile='files/parallel-native-report.json'}
Run-Checked $adb @('-s',$Serial,'shell','run-as',$target,'rm','-f',$reportFile)
$runner='PluginInstrumentation'
if($ProgressOnly){$runner='ProgressUiInstrumentation'}
if($LibraryOnly){$runner='LibraryUiInstrumentation'}
if($HostPlugin){$runner='HostPluginInstrumentation'}
if($CrownOnly){$runner='CrownScrollInstrumentation';$reportFile='files/crown-scroll-report.json'}
if($ParallelOnly){$runner='ParallelDownloadInstrumentation'}
if($NativeParallelOnly){$runner='NativeParallelInstrumentation'}
Run-Checked $adb @('-s',$Serial,'shell','am','instrument','-w',($testPackage+'/com.watchreader.fanqie.'+$runner))
$reportName='emulator-tests.json'
if($PhysicalWatch){$reportName='watch-tests.json'}
if($ProgressOnly){$reportName='watch-progress-ui.json'}
if($LibraryOnly){$reportName='watch-library-ui.json'}
if($HostPlugin){$reportName='watch-host-plugin.json'}
if($CrownOnly){$reportName='watch-crown-scroll.json'}
if($ParallelOnly){$reportName='watch-parallel-download.json'}
if($NativeParallelOnly){$reportName='watch-parallel-native.json'}
& $adb -s $Serial exec-out run-as $target cat $reportFile | Set-Content (Join-Path $moduleRoot ('test-results\'+$reportName)) -Encoding utf8
$report=Get-Content (Join-Path $moduleRoot ('test-results\'+$reportName)) -Raw -Encoding UTF8 | ConvertFrom-Json
if($report.failures.Count -gt 0){throw "Emulator checks failed: $($report.failures.Count)"}
