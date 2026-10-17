param([switch]$HostApp,[switch]$Debug, [string]$Sdk='', [string]$JdkHome='')
$ErrorActionPreference='Stop'
$moduleRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot=Split-Path $moduleRoot -Parent
. (Join-Path $repoRoot 'tools\env.ps1')
if(-not $Sdk){$Sdk=Get-WrEnv 'Sdk'}
if(-not $JdkHome){$JdkHome=Get-WrEnv 'JdkHome'}
$env:JAVA_HOME=$JdkHome
$env:PATH=(Join-Path $JdkHome 'bin')+';'+$env:PATH
$build=Join-Path $moduleRoot ('build\'+[guid]::NewGuid().ToString('N'))
$dist=Join-Path $moduleRoot 'dist'
@($build,$dist,(Join-Path $build 'classes'),(Join-Path $build 'dex'),(Join-Path $build 'gen')) | ForEach-Object {New-Item -ItemType Directory -Force -Path $_ | Out-Null}
$bt=Join-Path $Sdk 'build-tools\34.0.0'
$jar=Join-Path $Sdk 'platforms\android-33\android.jar'
function Run-Checked([string]$exe,[string[]]$arguments){
    $ErrorActionPreference='Continue'
    & $exe @arguments 2>&1 | ForEach-Object {Write-Host $_}
    if($LASTEXITCODE -ne 0){throw "Build step failed: $exe ($LASTEXITCODE)"}
}
$manifestSource=Join-Path $moduleRoot 'android\AndroidManifest.xml'
$resSource=Join-Path $moduleRoot 'android\res'
if($HostApp){$manifestSource=Join-Path $repoRoot 'android\app\AndroidManifest.xml';$resSource=Join-Path $repoRoot 'android\app\res'}
$manifest=Get-Content -LiteralPath $manifestSource -Raw -Encoding UTF8
if($HostApp){$manifest=$manifest.Replace('android:debuggable="true"','android:debuggable="false"')}
$appVersion=([xml]$manifest).manifest.GetAttribute('versionName','http://schemas.android.com/apk/res/android')
if($Debug){$manifest=$manifest.Replace('android:debuggable="false"','android:debuggable="true"')}
$manifestPath=Join-Path $build 'AndroidManifest.xml'
[IO.File]::WriteAllText($manifestPath,$manifest,[Text.UTF8Encoding]::new($false))
Run-Checked (Join-Path $bt 'aapt2.exe') @('compile','--dir',$resSource,'-o',(Join-Path $build 'resources.zip'))
Run-Checked (Join-Path $bt 'aapt2.exe') @('link','-o',(Join-Path $build 'base.apk'),'-I',$jar,'--manifest',$manifestPath,'--java',(Join-Path $build 'gen'),'--min-sdk-version','29','--target-sdk-version','29',(Join-Path $build 'resources.zip'))
$sourceRoots=@((Join-Path $moduleRoot 'api\java'),(Join-Path $moduleRoot 'android\java'),(Join-Path $build 'gen'))
if($HostApp){$sourceRoots=@((Join-Path $repoRoot 'android\app\java'),(Join-Path $moduleRoot 'api\java'),(Join-Path $moduleRoot 'host\java'),(Join-Path $build 'gen'))}
$sources=@(Get-ChildItem $sourceRoots -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
Run-Checked (Join-Path $JdkHome 'bin\javac.exe') (@('-encoding','UTF-8','--release','11','-nowarn','-classpath',$jar,'-d',(Join-Path $build 'classes'))+$sources)
$classes=@(Get-ChildItem (Join-Path $build 'classes') -Filter '*.class' -Recurse | Select-Object -ExpandProperty FullName)
Run-Checked (Join-Path $JdkHome 'bin\jar.exe') @('cf',(Join-Path $build 'classes.jar'),'-C',(Join-Path $build 'classes'),'.')
Run-Checked (Join-Path $bt 'd8.bat') @('--min-api','29','--output',(Join-Path $build 'dex'),'--lib',$jar,(Join-Path $build 'classes.jar'))
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::Open((Join-Path $build 'base.apk'),'Update')
try{Get-ChildItem (Join-Path $build 'dex') -Filter '*.dex' | ForEach-Object {[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$_.FullName,$_.Name)|Out-Null}}finally{$archive.Dispose()}
Run-Checked (Join-Path $bt 'zipalign.exe') @('-f','4',(Join-Path $build 'base.apk'),(Join-Path $build 'aligned.apk'))
$keystore=Join-Path $moduleRoot 'build\local-signing.keystore'
$secretFile=Join-Path $moduleRoot 'build\local-signing.password'
if(-not(Test-Path -LiteralPath $secretFile)){
    $password=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
    [IO.File]::WriteAllText($secretFile,$password)
    & icacls.exe $secretFile /inheritance:r /grant:r "${env:USERNAME}:(F)" | Out-Null
}
if(-not(Test-Path -LiteralPath $keystore)){
    Run-Checked (Join-Path $JdkHome 'bin\keytool.exe') @('-genkeypair','-keystore',$keystore,'-storepass:file',$secretFile,'-keypass:file',$secretFile,'-alias','fanqie-watch','-keyalg','RSA','-keysize','2048','-validity','10950','-dname','CN=WatchReader Fanqie Local, O=WatchReader, C=CN')
}
$name="WatchReader-Fanqie-$appVersion.apk"
if($Debug){$name="WatchReader-Fanqie-$appVersion-debug.apk"}
if($HostApp){$name="WatchReader-$appVersion.apk";if($Debug){$name="WatchReader-$appVersion-debug.apk"}}
$apk=Join-Path $dist $name
if($HostApp){
    $hostKey=Join-Path $repoRoot 'android\build\debug.keystore'
    if(-not(Test-Path -LiteralPath $hostKey)){throw 'Existing WatchReader signing identity is required; no replacement key will be generated.'}
    Run-Checked (Join-Path $bt 'apksigner.bat') @('sign','--ks',$hostKey,'--ks-pass','pass:android','--ks-key-alias','watchreader','--out',$apk,(Join-Path $build 'aligned.apk'))
}else{Run-Checked (Join-Path $bt 'apksigner.bat') @('sign','--ks',$keystore,'--ks-pass',"file:$secretFile",'--ks-key-alias','fanqie-watch','--out',$apk,(Join-Path $build 'aligned.apk'))}
Run-Checked (Join-Path $bt 'apksigner.bat') @('verify',$apk)
$latest='build\latest-build.txt';if($HostApp){$latest='build\latest-host-build.txt'}
[IO.File]::WriteAllText((Join-Path $moduleRoot $latest),$build)
Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
Write-Host "APK: $apk"
