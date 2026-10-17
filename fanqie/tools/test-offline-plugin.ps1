param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\..\tools\env.ps1')
$adb=Get-WrEnv 'Adb'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$user=((& $adb -s $Serial shell dumpsys package com.watchreader.fanqie)|Where-Object{$_ -match 'User 0:'}|Select-Object -First 1)
if($user -notmatch 'enabled=(\d+)'){throw 'Cannot capture original plugin enabled state.'}
$original=[int]$Matches[1]
try{
    & $adb -s $Serial shell pm disable-user --user 0 com.watchreader.fanqie | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not temporarily disable plugin.'}
    & $adb -s $Serial shell am instrument -w com.watchreader.integration.tests/com.watchreader.fanqie.OfflinePluginInstrumentation
    & $adb -s $Serial exec-out run-as com.watchreader cat files/offline-plugin-report.json | Set-Content (Join-Path $root 'test-results\watch-offline-plugin.json') -Encoding utf8
    $report=Get-Content (Join-Path $root 'test-results\watch-offline-plugin.json') -Raw -Encoding utf8 | ConvertFrom-Json
    if($report.failures.Count -gt 0){throw 'Offline plugin integration checks failed.'}
}finally{
    $restore=@{0='default-state';1='enable';2='disable';3='disable-user';4='disable-until-used'}[$original]
    if(-not $restore){throw 'Unknown original plugin state.'}
    & $adb -s $Serial shell pm $restore --user 0 com.watchreader.fanqie | Out-Null
    $after=((& $adb -s $Serial shell dumpsys package com.watchreader.fanqie)|Where-Object{$_ -match 'User 0:'}|Select-Object -First 1)
    if($after -notmatch ('enabled='+$original+'\b')){throw 'Plugin state restoration failed.'}
    Write-Host "Plugin restored to original enabled=$original"
}
