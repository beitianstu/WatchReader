$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $root '..\tools\env.ps1')
$jdk=Join-Path (Get-WrEnv 'JdkHome') 'bin'
$classes=Join-Path $root 'test-results\progress-jvm'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$source=Join-Path $root 'host\java\com\watchreader\fanqie'
$api=Join-Path $root 'api\java\com\watchreader\fanqie'
& (Join-Path $jdk 'javac.exe') -encoding UTF-8 --release 11 -d $classes (Join-Path $api 'ReaderPlugin.java') (Join-Path $source 'ProgressSync.java') (Join-Path $source 'ChapterPosition.java') (Join-Path $root 'tests\java\com\watchreader\fanqie\ProgressSyncChecks.java')
if($LASTEXITCODE -ne 0){throw 'Progress tests failed to compile.'}
$result=& (Join-Path $jdk 'java.exe') -cp $classes com.watchreader.fanqie.ProgressSyncChecks
if($LASTEXITCODE -ne 0){throw 'Progress sync checks failed.'}
$result | Set-Content (Join-Path $root 'test-results\progress-sync-checks.json') -Encoding utf8
Write-Output $result
