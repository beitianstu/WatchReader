param([Parameter(Mandatory=$true)][string]$Serial,[Parameter(Mandatory=$true)][string]$Apk)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\..\tools\env.ps1')
$adb=Get-WrEnv 'Adb'
if(-not(Test-Path -LiteralPath $Apk)){throw 'APK not found.'}
$installer='com.android.packageinstaller'
$dump=(& $adb -s $Serial shell dumpsys package $installer) -join "`n"
$userLine=($dump -split "`n" | Where-Object {$_ -match 'User 0:'} | Select-Object -First 1)
if($userLine -notmatch 'enabled=(\d+)'){throw 'Cannot capture original installer state.'}
$original=[int]$Matches[1]
try{
    & $adb -s $Serial shell pm disable-user --user 0 $installer | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Temporary installer disable failed.'}
    & $adb -s $Serial install --no-incremental -r $Apk
    if($LASTEXITCODE -ne 0){throw 'Watch APK installation failed.'}
}finally{
    $restore=@{0='default-state';1='enable';2='disable';3='disable-user';4='disable-until-used'}[$original]
    if(-not $restore){throw 'Unknown original installer state.'}
    & $adb -s $Serial shell pm $restore --user 0 $installer | Out-Null
    $after=((& $adb -s $Serial shell dumpsys package $installer) | Where-Object {$_ -match 'User 0:'} | Select-Object -First 1)
    if($after -notmatch ('enabled='+$original+'\b')){throw 'Installer state restoration could not be verified.'}
    Write-Host "Installer restored to original enabled=$original"
}
