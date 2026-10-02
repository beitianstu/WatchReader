# 共用环境解析：Android SDK / JDK / adb / 手表地址
#
# 被其它脚本点源使用：  . (Join-Path $PSScriptRoot 'env.ps1')
#
# 解析优先级（每一项都是这个顺序）：
#   1) 命令行参数（调用方在点源之后覆盖）
#   2) 环境变量（ANDROID_HOME / ANDROID_SDK_ROOT / JAVA_HOME / WATCH_ADB_TARGET）
#   3) 常见安装位置探测
#   4) 报错并给出解决提示
#
# 这样脚本不再依赖某一台机器的具体路径，换机器只需设环境变量。

$script:WrEnv = @{}

function Get-WrEnv {
    param([string]$Name)

    if ($script:WrEnv.ContainsKey($Name)) { return $script:WrEnv[$Name] }

    $value = switch ($Name) {
        'Sdk' {
            $candidates = @(
                $env:ANDROID_HOME,
                $env:ANDROID_SDK_ROOT,
                'D:\Android\android-sdk',
                "$env:LOCALAPPDATA\Android\Sdk",
                "$env:USERPROFILE\AppData\Local\Android\Sdk",
                'C:\Android\sdk'
            )
            ($candidates | Where-Object {
                $_ -and (Test-Path (Join-Path $_ 'platform-tools\adb.exe'))
            } | Select-Object -First 1)
        }
        'JdkHome' {
            $candidates = @($env:JAVA_HOME, 'D:\Android\openjdk\jdk-17.0.8.101-hotspot') +
                @(Get-ChildItem 'D:\Android\openjdk', "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium" `
                    -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName) +
                @(Get-ChildItem "$env:ProgramFiles\Microsoft" -Directory -Filter 'jdk*' -ErrorAction SilentlyContinue |
                    Select-Object -ExpandProperty FullName)
            ($candidates | Where-Object {
                $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe'))
            } | Select-Object -First 1)
        }
        'Adb' {
            # 优先 PATH 里的 adb（最通用），其次各 SDK 位置，最后工具包自带。
            # 注意：不用内联 if 构造数组 —— PS 5.1 不支持 if 作为表达式。
            $candidates = New-Object System.Collections.ArrayList
            $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue |
                Select-Object -First 1 -ExpandProperty Source
            if ($onPath) { [void]$candidates.Add($onPath) }
            if ($env:ANDROID_HOME) { [void]$candidates.Add((Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')) }
            if ($env:ANDROID_SDK_ROOT) { [void]$candidates.Add((Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe')) }
            [void]$candidates.Add('D:\Android\android-sdk\platform-tools\adb.exe')
            [void]$candidates.Add("$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
            [void]$candidates.Add("$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe")
            ($candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1)
        }
        'Target' {
            # 手表无线调试地址；可用 WATCH_ADB_TARGET 覆盖
            if ($env:WATCH_ADB_TARGET) { $env:WATCH_ADB_TARGET }
            else { '192.168.3.213:5555' }
        }
        'Apk' {
            Join-Path (Split-Path $PSScriptRoot -Parent) 'android\build\apk\WatchReader.apk'
        }
        default { $null }
    }

    $script:WrEnv[$Name] = $value
    return $value
}

# 典型的 adb 安装位置提示（报错时用）
function Get-WrAdbHint {
    @'
请任选一种方式让脚本找到 adb：
  · 设环境变量： $env:ANDROID_HOME = 'D:\Android\android-sdk'
  · 把 platform-tools 加进 PATH
  · 调用时显式传入： -Adb 'D:\Android\android-sdk\platform-tools\adb.exe'
adb 随 Android SDK 的 platform-tools 一起分发，也可单独下载。
'@
}

function Get-WrJdkHint {
    @'
请任选一种方式让脚本找到 JDK（d8 / apksigner 需要 JDK 11 或 17）：
  · 设环境变量： $env:JAVA_HOME = 'D:\Android\openjdk\jdk-17.0.8.101-hotspot'
  · 调用时显式传入： -JdkHome 'C:\Program Files\Java\jdk-17'
'@
}

function Get-WrDeviceList {
    <#
      解析 `adb devices` 的可用设备序列号。

      刻意用**显式循环 + 明确比较**，而不是管道里的 Select-String / ForEach 混用：
      实测踩过坑 —— `Select-String ... | ForEach-Object { ($_ -split '\s+')[0] }` 会把
      MatchInfo 对象转成 "文件:行号:行内容"（在脚本里得到 "1"）而不是设备序列号。
      $Adb 需要以 "$" + "Adb" 的拼接方式传参，避免与自动变量 $args 之类混淆。
    #>
    param([string]$AdbExe)

    $result = New-Object System.Collections.ArrayList
    $raw = & $AdbExe devices 2>$null
    foreach ($line in $raw) {
        $s = [string]$line
        if ($s.Trim() -eq '' -or $s -like 'List of devices*') { continue }
        $parts = $s.Trim() -split '\s+'
        if ($parts.Count -ge 2 -and $parts[1] -eq 'device') {
            [void]$result.Add($parts[0])
        }
    }
    # 关键：必须用逗号包一层。
    # PowerShell 在函数返回"单个元素的集合"时会把它解包成标量 ——
    # 于是调用方拿到的是字符串，$list[0] 就变成首字符（实测拿到 '1' 而不是设备号）。
    return , $result
}

function Test-WrDeviceOnline {
    param([string]$AdbExe, [string]$Serial)
    $list = Get-WrDeviceList $AdbExe
    foreach ($d in $list) { if ($d -eq $Serial) { return $true } }
    return $false
}

function Get-WrDeviceState {
    param([string]$AdbExe, [string]$Serial)
    $raw = & $AdbExe devices 2>$null
    foreach ($line in $raw) {
        $s = [string]$line
        $parts = $s.Trim() -split '\s+'
        if ($parts.Count -ge 2 -and $parts[0] -eq $Serial) { return $parts[1] }
    }
    return ''
}
function Assert-WrAdb {
    param([string]$Adb)
    if (-not $Adb -or -not (Test-Path $Adb)) {
        $msg = "找不到 adb" + $(if ($Adb) { "：$Adb" } else { '' })
        throw ("$msg`n" + (Get-WrAdbHint))
    }
    return $Adb
}

function Assert-WrSdk {
    param([string]$Sdk)
    if (-not $Sdk -or -not (Test-Path $Sdk)) {
        throw ("找不到 Android SDK" + $(if ($Sdk) { "：$Sdk" } else { '' }) + "`n" +
               "请设 `$env:ANDROID_HOME，或用 -Sdk 指定（需含 build-tools 与 platforms）。")
    }
    return $Sdk
}

function Assert-WrJdk {
    param([string]$JdkHome)
    if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin\javac.exe'))) {
        throw ("找不到 JDK" + $(if ($JdkHome) { "：$JdkHome" } else { '' }) + "`n" + (Get-WrJdkHint))
    }
    return $JdkHome
}
