# MagicOriG 任务4收官验证脚本（设备中心降噪适配）
# 用法: pwsh tools/final_verify.ps1 [-BallX 640] [-BallY 1400] [-SkipInstall]
param(
    [int]$BallX = 640,
    [int]$BallY = 1400,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Continue'
$adb = 'D:\ADB\platform-tools\adb.exe'
$nl = "`r?`n"
$root = 'D:\Documents\MagicOrigin'

function Shot([string]$out) {
    for ($i = 1; $i -le 6; $i++) {
        $dev = (& $adb devices 2>$null) -join ''
        if ($dev -notmatch 'device$') { Start-Sleep 4; continue }
        & $adb shell 'rm -f /sdcard/u.png' 2>$null | Out-Null
        Start-Sleep 2
        & $adb shell 'screencap -p /sdcard/u.png' 2>$null | Out-Null
        & $adb shell 'chmod 666 /sdcard/u.png' 2>$null | Out-Null
        if (Test-Path $out) { Remove-Item -Force $out }
        & $adb pull /sdcard/u.png $out 2>$null | Out-Null
        $f = Get-Item $out -ErrorAction SilentlyContinue
        if ($f -and $f.Length -gt 5000) { return $f.Length }
        Start-Sleep 4
    }
    return 0
}
function Logs([string]$pat) {
    $h = (& $adb shell "su -c 'grep -a -e $pat /data/adb/lspd/log/modules_*.log | tail -10'" 2>$null) -split $nl
    $h | ForEach-Object { $s = $_.Trim(); if ($s) { "  " + ($s -replace '^.*\]\s*', '') } }
}

Write-Host '== 1. 连接检查 =='
& $adb start-server 2>$null | Out-Null
Start-Sleep 3
$d = (& $adb devices 2>$null) -join ''
if ($d -notmatch 'device$') { Write-Host "OFFLINE: $d"; exit 1 }
Write-Host "OK: $d"

if (-not $SkipInstall) {
    Write-Host '== 2. 部署 =='
    $r = & $adb install -r "$root\app\build\outputs\apk\release\app-release.apk" 2>&1 | Out-String
    Write-Host $r.Trim()
}

Write-Host '== 3. 拉起设备中心 =='
& $adb shell 'am force-stop com.hihonor.controlcenter' 2>$null | Out-Null
Start-Sleep 3
& $adb shell "su -c 'am start -a honor.action.control_home'" 2>$null | Out-Null
Start-Sleep 14

Write-Host '== 4. 关 USB 对话框 =='
& $adb shell 'input tap 370 2519' 2>$null | Out-Null
Start-Sleep 2

Write-Host '== 5. 定位截图 =='
$n = Shot "$root\step_locate.png"
Write-Host "shot: ${n}B (看图确认耳机球坐标，不对就改 -BallX/-BallY 重跑)"

Write-Host "== 6. 点耳机球 ($BallX,$BallY) =="
& $adb shell "input tap $BallX $BallY" 2>$null | Out-Null
Start-Sleep 8

Write-Host '== 7. 卡片截图 =='
$n = Shot "$root\step_card.png"
Write-Host "shot: ${n}B"

Write-Host '== 8. 关键日志（期望: 注入成功 / 无 CNFE / SEC / REMOTE_EARPHONE）=='
Logs '注入成功|ClassNotFoundException|createEarphone|REMOTE_EARPHONE|setNoiseCtrlMode|onNoiseModeClick'

Write-Host ''
Write-Host '若卡片已显示「噪声控制」三按钮 → 点按钮（可用截图给坐标），再跑 Logs 查:'
Write-Host '  期望: setNoiseCtrlMode(deviceId=18:5C.., nc=1) → 广播 status=5 → bluetooth SPP 4E..10 00'
