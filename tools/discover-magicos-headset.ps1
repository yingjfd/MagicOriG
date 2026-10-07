<#
.SYNOPSIS
    在真机上探测 MagicOS 蓝牙/设置里与耳机、降噪相关的类名与方法名。

.DESCRIPTION
    本模块从 HyperOriG（小米 HyperOS）移植，现有 hook 目标都是 MIUI 类名：
        com.android.settings.bluetooth.HeadsetIDConstants
        com.android.bluetooth.ble.app.headset.BluetoothHeadsetService
        com.android.bluetooth.ble.app.MiuiBluetoothNotification
        com.android.bluetooth.ble.app.IMiuiHeadsetCallback
    这些类在荣耀 MagicOS 上极可能不存在，而现有代码全部用 runCatching 包裹，
    结果就是「一切看起来正常，但降噪面板根本不出现」。

    本脚本把目标 APK 拉下来，解出 classes*.dex，在 dex 字符串池里做 ASCII 子串扫描，
    列出命中的关键字及其所在字符串的完整上下文，用来确定 MagicOS 上真实的 hook 目标。

    用法（手机连上 adb 即可，系统 APK 若对 shell 不可见则需要 root）：

      powershell -ExecutionPolicy Bypass -File tools\discover-magicos-headset.ps1
      powershell -ExecutionPolicy Bypass -File tools\discover-magicos-headset.ps1 -Package com.android.settings
      powershell -ExecutionPolicy Bypass -File tools\discover-magicos-headset.ps1 -Package com.hihonor.android.bluetooth -Pattern changeAncMode,NoiseCancel
#>

[CmdletBinding()]
param(
    [string]$Adb = 'adb',

    # 不指定时自动列出设备上所有含 honor/bluetooth/settings/headset/earphone 的包
    [string[]]$Package = @(),

    # 要在 dex 字符串池里搜的 ASCII 关键字（可按需增删）
    [string[]]$Pattern = @(
        'HeadsetIDConstants', 'checkSupport', 'changeAncMode', 'isMiTWS',
        'BluetoothHeadsetService', 'IMiuiHeadsetCallback', 'MiuiBluetoothNotification',
        'getDeviceInfo', 'setCommonCommand', 'refreshStatus',
        'NoiseCancel', 'NoiseCancelling', 'AncMode', 'ANC_MODE',
        'Transparency', 'Headset', 'Earphone', 'SmartHeadset',
        'HeadsetPlugin', 'DeviceConfig'
    ),

    # 解压/落地目录
    [string]$OutDir = (Join-Path $PSScriptRoot '..\build\discovery')
)

# 不能用 'Stop'：adb 会往 stderr 写 "* daemon not running ..." 等信息，
# PowerShell 会转成 ErrorRecord，Stop 模式下会直接中断整个脚本。
$ErrorActionPreference = 'Continue'

# 取命中位置所在的整段可打印字符串（dex 字符串池里就是完整的类名/方法名）
function Get-StringContext {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][int]$Index,
        [int]$Max = 240
    )
    $start = $Index
    while ($start -gt 0 -and ($Index - $start) -lt $Max) {
        $c = [int]$Text[$start - 1]
        if ($c -lt 0x20 -or $c -gt 0x7E) { break }
        $start--
    }
    $end = $Index
    while ($end -lt ($Text.Length - 1) -and ($end - $Index) -lt $Max) {
        $c = [int]$Text[$end + 1]
        if ($c -lt 0x20 -or $c -gt 0x7E) { break }
        $end++
    }
    return $Text.Substring($start, $end - $start + 1)
}

# ── 0. adb 可用性 ────────────────────────────────────────────────
try {
    $version = (& $Adb version 2>$null | Select-Object -First 1)
    Write-Host "adb: $version"
} catch {
    Write-Host "[FAIL] 找不到 adb，请把 platform-tools 加入 PATH，或用 -Adb 指定完整路径"
    exit 1
}

$devices = (& $Adb devices 2>$null) -split "`n" |
    Select-Object -Skip 1 |
    Where-Object { $_ -match "`tdevice" }
if (-not $devices) {
    Write-Host "[FAIL] 没有已连接且可用的设备"
    exit 1
}
Write-Host ("设备数: {0}" -f @($devices).Count)
Write-Host ""

# ── 1. 自动列出候选包 ────────────────────────────────────────────
if (-not $Package -or $Package.Count -eq 0) {
    Write-Host "── 自动扫描候选包 ──"
    $all = (& $Adb shell pm list packages 2>$null) -split "`r?`n" |
        ForEach-Object { $_.Trim() -replace '^package:', '' } |
        Where-Object { $_ -match 'honor|hihonor|^hw|bluetooth|settings|headset|earphone' }
    $Package = @($all | Sort-Object)
    $Package | ForEach-Object { Write-Host "  $_" }
    Write-Host ""
    if (-not $Package) {
        Write-Host "[FAIL] 没有匹配到候选包，请用 -Package 手动指定"
        exit 1
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$latin1 = [System.Text.Encoding]::GetEncoding(28591)
$hitCount = 0
$scannedDex = 0
$foundNames = New-Object System.Collections.Generic.List[string]

# ── 2. 逐包拉取并扫描 ────────────────────────────────────────────
foreach ($pkg in $Package) {
    $apkPaths = (& $Adb shell pm path $pkg 2>$null) -split "`r?`n" |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -like 'package:*' } |
        ForEach-Object { $_ -replace '^package:', '' }

    if (-not $apkPaths) {
        Write-Host "[WARN] ${pkg}: pm path 无结果（未安装，或系统 APK 对 shell 不可见）"
        continue
    }

    foreach ($apkPath in $apkPaths) {
        $leaf = ($apkPath -replace '/', '_').TrimStart('_')
        $localApk = Join-Path $OutDir $leaf

        Write-Host "── $pkg  ($apkPath) ──"
        & $Adb pull $apkPath $localApk 2>$null | Out-Null
        if (-not (Test-Path $localApk)) {
            Write-Host "  [FAIL] 拉取失败（系统 APK 通常需要 root 才能读）"
            continue
        }

        $dexDir = Join-Path $OutDir ($leaf + '_dex')
        if (Test-Path $dexDir) { Remove-Item -Recurse -Force $dexDir }
        New-Item -ItemType Directory -Force -Path $dexDir | Out-Null

        try {
            Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
            $zip = [System.IO.Compression.ZipFile]::OpenRead($localApk)
            try {
                foreach ($entry in $zip.Entries) {
                    if ($entry.FullName -match '^classes\d*\.dex$') {
                        [System.IO.Compression.ZipFileExtensions]::ExtractToFile(
                            $entry, (Join-Path $dexDir $entry.FullName), $true)
                    }
                }
            } finally { $zip.Dispose() }
        } catch {
            Write-Host "  [FAIL] 解压 dex 失败: $($_.Exception.Message)"
            continue
        }

        $dexFiles = @(Get-ChildItem -Path $dexDir -Filter 'classes*.dex' -ErrorAction SilentlyContinue)
        if ($dexFiles.Count -eq 0) {
            Write-Host "  [INFO] 该 APK 没有 classes*.dex（纯资源或 split APK）"
            continue
        }

        foreach ($dex in $dexFiles) {
            $bytes = [System.IO.File]::ReadAllBytes($dex.FullName)
            $text = $latin1.GetString($bytes)
            $bytes = $null          # 尽早释放，字符串已承载全部内容
            $scannedDex++

            foreach ($p in $Pattern) {
                if ([string]::IsNullOrEmpty($p)) { continue }
                $count = 0
                $contexts = New-Object System.Collections.Generic.List[string]
                $idx = $text.IndexOf($p, [StringComparison]::Ordinal)
                while ($idx -ge 0) {
                    $count++
                    if ($contexts.Count -lt 3) { $contexts.Add((Get-StringContext -Text $text -Index $idx)) }
                    $next = $idx + 1
                    if ($next -ge $text.Length) { break }
                    $idx = $text.IndexOf($p, $next, [StringComparison]::Ordinal)
                }
                if ($count -gt 0) {
                    $hitCount++
                    if (-not $foundNames.Contains($p)) { $foundNames.Add($p) }
                    Write-Host ("  [HIT] {0}  x{1}  ({2})" -f $p, $count, $dex.Name)
                    foreach ($c in $contexts) { Write-Host "          $c" }
                }
            }
            $text = $null
        }
    }
    Write-Host ""
}

# ── 3. 结论 ──────────────────────────────────────────────────────
Write-Host "──────── 结果 ────────"
Write-Host "扫描 dex 文件数: $scannedDex"
Write-Host "命中模式数:      $hitCount"
if ($hitCount -eq 0) {
    Write-Host ""
    Write-Host "没有任何命中 —— 这些 MIUI 类名在本机系统里不存在。"
    Write-Host "下一步："
    Write-Host "  1) 换关键字重跑，例如 -Pattern 耳机页里实际出现的文案对应的常量"
    Write-Host "  2) 用 jadx/dexdump 反编译 $OutDir 下已拉取的 APK，直接看类结构"
    Write-Host "  3) 把确认的类名填回 HookEntry.kt / BluetoothUpstreamHeadsetHook.kt"
    exit 2
}
Write-Host ""
Write-Host ("命中关键字: {0}" -f ($foundNames -join ', '))
Write-Host "解出的 dex 在: $OutDir"
Write-Host "把确认的类名替换掉 HookEntry.kt / BluetoothUpstreamHeadsetHook.kt 里的 MIUI 目标。"
exit 0
