# MagicOriG 全量验证脚本 —— 设备一回来跑这个
# 用法: powershell -ExecutionPolicy Bypass -File tools\full_verify.ps1
param([switch]$SkipInstall)
$ErrorActionPreference = 'Continue'
$adb = 'D:\ADB\platform-tools\adb.exe'
$nl = "`r?`n"
$root = 'D:\Documents\MagicOrigin'
$fail = @()

function Shot([string]$out) {
    for ($i = 1; $i -le 8; $i++) {
        $d = (& $script:adb devices 2>$null) -join ''
        if ($d -notmatch 'device$') { Start-Sleep 4; continue }
        & $script:adb shell 'rm -f /sdcard/u.png' 2>$null | Out-Null
        Start-Sleep 2
        & $script:adb shell 'screencap -p /sdcard/u.png' 2>$null | Out-Null
        & $script:adb shell 'chmod 666 /sdcard/u.png' 2>$null | Out-Null
        if (Test-Path $out) { Remove-Item -Force $out }
        & $script:adb pull /sdcard/u.png $out 2>$null | Out-Null
        $f = Get-Item $out -ErrorAction SilentlyContinue
        if ($f -and $f.Length -gt 5000) { return $f.Length }
        Start-Sleep 4
    }
    return 0
}
function LogQ([string]$pat) {
    # pattern 含空格会被 su -c 内的 shell 拆词 → 转成正则通配（无空格）规避
    $p = $pat -replace ' ', '.{0,2}'
    (& $script:adb shell "su -c 'grep -a -e $p /data/adb/lspd/log/modules_*.log | tail -10'" 2>$null) -split $nl |
        ForEach-Object { $s = $_.Trim(); if ($s) { ($s -replace '^\[ [\d-T:.]+ \S+ \S+ \S+ /LSPosedFramework.\] ', '') } }
}

Write-Host '════ 1. 连接 & 部署 ════'
& $adb start-server 2>$null | Out-Null
Start-Sleep 3
$d = (& $adb devices 2>$null) -join ''
if ($d -notmatch 'device$') { Write-Host "OFFLINE: $d"; exit 1 }
Write-Host "device: OK"
if (-not $SkipInstall) {
    $r = & $adb install -r "$root\app\build\outputs\apk\release\app-release.apk" 2>&1 | Out-String
    Write-Host $r.Trim()
}

Write-Host '════ 2. 基础信号 ════'
Write-Host "connected flag: $((& $adb shell 'settings get global magicorig_connected' 2>$null|Out-String).Trim())"
Write-Host "battery:        $((& $adb shell 'settings get global magicorig_battery' 2>$null|Out-String).Trim())"
Write-Host "current_anc:    $((& $adb shell 'settings get global magicorig_current_anc' 2>$null|Out-String).Trim())"
Write-Host "last_anc:       $((& $adb shell 'settings get global magicorig_last_anc' 2>$null|Out-String).Trim())"

Write-Host '════ 3. 重启相关进程 ════'
& $adb shell 'am force-stop com.hihonor.controlcenter' 2>$null | Out-Null
& $adb shell 'am force-stop com.hihonor.imedia.sws' 2>$null | Out-Null
Write-Host 'controlcenter + sws 已重启'

Write-Host '════ 4. 打开设备中心（关 USB 对话框 → 截图定位）════'
& $adb shell 'input keyevent KEYCODE_WAKEUP' 2>$null | Out-Null
Start-Sleep 1
& $adb shell "su -c 'am start -a honor.action.control_home'" 2>$null | Out-Null
Start-Sleep 13
& $adb shell 'input tap 370 2519' 2>$null | Out-Null
Start-Sleep 2
& $adb shell 'input tap 200 600' 2>$null | Out-Null
Start-Sleep 3
$n = Shot "$root\verify_1_home.png"
Write-Host "home shot: ${n}B  ← 看耳机球是否显示（任务5/6）"

Write-Host '════ 5. 点耳机球（默认 640,1400，可改）════'
& $adb shell 'input tap 640 1400' 2>$null | Out-Null
Start-Sleep 9
$n = Shot "$root\verify_2_card.png"
Write-Host "card shot: ${n}B  ← 看卡片电量(任务2) + 噪声控制三按钮 + 当前档位是否真实(bugA)"

Write-Host '════ 5b. 下拉控制中心面板（用户核心入口：面板里的设备中心卡片条球区）════'
& $adb shell 'input keyevent BACK' 2>$null | Out-Null
Start-Sleep 2
& $adb shell 'input swipe 1150 0 1150 1800 300' 2>$null | Out-Null
Start-Sleep 4
$n = Shot "$root\verify_3_panel.png"
Write-Host "panel shot: ${n}B  ← 看「设备中心」卡片条球区是否出现耳机球"
& $adb shell 'input keyevent BACK' 2>$null | Out-Null
Start-Sleep 2

Write-Host '════ 6. 关键日志 ════'
Write-Host '--- 打桩/电量 ---'
LogQ 'setBatteryLevel|genDeviceServiceInfo.setBatteryLevel' | Select-Object -Last 5 | ForEach-Object { "  $_" }
Write-Host '--- 卡片/档位 ---'
LogQ 'createDeviceCard ball|fromBatteryData 注入|真实 ANC 模式' | Select-Object -Last 5 | ForEach-Object { "  $_" }
Write-Host '--- 通知 ---'
LogQ 'SystemUI 通知改写' | Select-Object -Last 3 | ForEach-Object { "  $_" }

Write-Host ''
Write-Host '════ 剩余人工验证 ════'
Write-Host '1. 点「关闭」→ LogQ 查: setNoiseCtrlMode(nc=0) + SPP 4E 05...00 00 + 连点不卡'
Write-Host '2. 断连耳机 → 重开设备中心 → 球应消失（任务6反向）'
Write-Host '3. 通知栏 → 原道 OriG in / 左耳 x% 右耳 x%（任务4）'
Write-Host '4. 蓝牙设置页进入 → 无自动广播（bug3 Settings侧）'
Write-Host "截图目录: $root\verify_*.png"
