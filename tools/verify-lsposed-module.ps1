<#
.SYNOPSIS
    检查一个 APK 是否能被 LSPosed 识别为 Xposed 模块。

.DESCRIPTION
    完全复刻 LSPosed 官方管理器 app/src/main/java/org/lsposed/manager/util/ModuleUtil.java
    的识别逻辑，用它去检查你手上的 APK：

      modern  = APK 内存在 META-INF/xposed/java_init.list  -> getModernModuleApk()
      legacy  = AndroidManifest 元数据里有 xposedminversion -> isLegacyModule()
      模块被识别 = modern || legacy

    另外解析 module.prop（minApiVersion / targetApiVersion / staticScope）、
    java_init.list 入口类、scope.list，并检查作用域声明是否一致。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\verify-lsposed-module.ps1 -Apk app\build\outputs\apk\release\app-release.apk
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Apk,

    # 把任何不一致都当成失败退出（CI 用）
    [switch]$Strict
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

function Write-Check {
    param([string]$Status, [string]$Label, [string]$Detail = '')
    $mark = switch ($Status) {
        'PASS' { '[PASS]' }
        'FAIL' { '[FAIL]' }
        'WARN' { '[WARN]' }
        default { '[INFO]' }
    }
    if ($Detail) { "{0} {1} — {2}" -f $mark, $Label, $Detail }
    else { "{0} {1}" -f $mark, $Label }
}

$apkJar = (Resolve-Path -LiteralPath $Apk).Path
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkJar)
$failures = @()
$warnings = @()

try {
    Write-Host ""
    Write-Host "APK: $apkJar"
    Write-Host ("大小: {0:N0} 字节" -f (Get-Item -LiteralPath $apkJar).Length)
    Write-Host ""

    # ── 1. 现代模块识别：META-INF/xposed/java_init.list ────────────────
    $javaInitEntry = $zip.GetEntry('META-INF/xposed/java_init.list')
    $modern = $null -ne $javaInitEntry
    if ($modern) {
        $reader = New-Object System.IO.StreamReader($javaInitEntry.Open())
        try { $javaInitLines = ($reader.ReadToEnd() -split "`r?`n") | Where-Object { $_.Trim() -ne '' } }
        finally { $reader.Dispose() }
        Write-Check 'PASS' '现代模块识别 (java_init.list 存在)' ("{0} 个入口类" -f $javaInitLines.Count)
        foreach ($l in $javaInitLines) { Write-Host "         入口: $l" }
        if ($javaInitLines.Count -eq 0) {
            $failures += 'java_init.list 为空 —— LSPosed 能发现模块但加载不到入口'
            Write-Check 'FAIL' 'java_init.list 非空' '文件存在但没有任何类名'
        }
    } else {
        $failures += 'APK 内没有 META-INF/xposed/java_init.list —— 现代模块识别失败'
        Write-Check 'FAIL' '现代模块识别 (java_init.list)' '未找到该条目'
    }

    # ── 2. module.prop ────────────────────────────────────────────────
    $prop = @{}
    $propEntry = $zip.GetEntry('META-INF/xposed/module.prop')
    if ($propEntry) {
        $reader = New-Object System.IO.StreamReader($propEntry.Open(), [System.Text.Encoding]::UTF8)
        try { $propText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        foreach ($line in ($propText -split "`r?`n")) {
            if ($line -match '^\s*([^#!=\s][^=]*)=(.*)$') {
                $prop[$Matches[1].Trim()] = $Matches[2].Trim()
            }
        }
        Write-Check 'PASS' 'module.prop 存在'
        foreach ($k in ($prop.Keys | Sort-Object)) { Write-Host ("         {0} = {1}" -f $k, $prop[$k]) }
    } else {
        $warnings += '没有 META-INF/xposed/module.prop，LSPosed 会退回默认值 minApiVersion=100 / targetApiVersion=100 / staticScope=false'
        Write-Check 'WARN' 'module.prop' '缺失，将使用默认值 min=100 target=100 staticScope=false'
    }

    # module.prop 关键字段（对应 ModuleUtil.InstalledModule 构造函数）
    if ($propEntry) {
        if (-not $prop.ContainsKey('minApiVersion')) {
            $failures += 'module.prop 缺少 minApiVersion'
            Write-Check 'FAIL' 'minApiVersion' '缺失'
        }
        if (-not $prop.ContainsKey('targetApiVersion')) {
            # ModuleUtil 里 targetVersion = extractIntPart(prop.getProperty("targetApiVersion"))
            # getProperty 返回 null 时 extractIntPart(null) 会 NPE，导致整个模块列表加载失败
            $warnings += 'module.prop 缺少 targetApiVersion —— 若 LSPosed 的 extractIntPart 未做空值保护，这会让模块列表整体加载失败'
            Write-Check 'WARN' 'targetApiVersion' '缺失（建议显式声明，规避 LSPosed 的 NPE 风险）'
        } else {
            Write-Check 'PASS' 'targetApiVersion' $prop['targetApiVersion']
        }
        $staticScope = $prop.ContainsKey('staticScope') -and $prop['staticScope'] -eq 'true'
        Write-Check 'INFO' 'staticScope' ("{0}（true = 静态作用域；无论取值如何，作用域始终取自 scope.list）" -f $(if ($staticScope) { 'true' } else { 'false' }))
    }

    # ── 3. scope.list ─────────────────────────────────────────────────
    $scopeEntry = $zip.GetEntry('META-INF/xposed/scope.list')
    $scopeList = @()
    if ($scopeEntry) {
        $reader = New-Object System.IO.StreamReader($scopeEntry.Open())
        try { $scopeList = ($reader.ReadToEnd() -split "`r?`n") | Where-Object { $_.Trim() -ne '' } }
        finally { $reader.Dispose() }
        Write-Check 'PASS' 'scope.list 存在' ("{0} 项" -f $scopeList.Count)
        foreach ($s in $scopeList) { Write-Host "         作用域: $s" }
        if ($scopeList.Count -eq 0) {
            $warnings += 'scope.list 为空 —— 现代模块的作用域完全由它决定，manifest 里的 xposedscope 会被忽略'
            Write-Check 'WARN' 'scope.list 非空' '文件存在但为空'
        }
    } else {
        $warnings += '没有 scope.list —— 现代模块的作用域会是空列表（manifest 的 xposedscope 对现代模块无效）'
        Write-Check 'WARN' 'scope.list' '缺失'
    }

    # ── 4. 其它 META-INF/xposed 条目 ──────────────────────────────────
    foreach ($e in @('META-INF/xposed/native_init.list')) {
        if ($zip.GetEntry($e)) { Write-Check 'INFO' $e '存在' }
    }

    # ── 5. 旧式模块识别：AndroidManifest.xml 元数据 ────────────────────
    $mfEntry = $zip.GetEntry('AndroidManifest.xml')
    $legacyKeys = @('xposedminversion', 'xposedmodule', 'xposeddescription', 'xposedscope')
    $foundLegacy = @()
    if ($mfEntry) {
        $ms = New-Object System.IO.MemoryStream
        $mfEntry.Open().CopyTo($ms)
        $bytes = $ms.ToArray()
        $ms.Dispose()

        # 二进制 XML 字符串池可能是 UTF-8 也可能是 UTF-16LE，两种都扫
        foreach ($key in $legacyKeys) {
            $u8 = [System.Text.Encoding]::UTF8.GetBytes($key)
            $u16 = [System.Text.Encoding]::Unicode.GetBytes($key)
            $hitU8 = $false; $hitU16 = $false
            for ($i = 0; $i -le $bytes.Length - $u8.Length; $i++) {
                if (-not $hitU8) {
                    $ok = $true
                    for ($j = 0; $j -lt $u8.Length; $j++) { if ($bytes[$i + $j] -ne $u8[$j]) { $ok = $false; break } }
                    if ($ok) { $hitU8 = $true }
                }
                if (-not $hitU16 -and $i -le $bytes.Length - $u16.Length) {
                    $ok = $true
                    for ($j = 0; $j -lt $u16.Length; $j++) { if ($bytes[$i + $j] -ne $u16[$j]) { $ok = $false; break } }
                    if ($ok) { $hitU16 = $true }
                }
                if ($hitU8 -and $hitU16) { break }
            }
            if ($hitU8 -or $hitU16) { $foundLegacy += $key }
        }
    }

    $legacy = $foundLegacy -contains 'xposedminversion'
    if ($legacy) {
        Write-Check 'PASS' '旧式模块识别 (xposedminversion 元数据)' ('找到: ' + ($foundLegacy -join ', '))
    } else {
        $WriteWarn = 'AndroidManifest 里没有 xposedminversion —— 旧式识别路径失效（若 java_init.list 也不在，模块就完全不会被识别）'
        Write-Check 'FAIL' '旧式模块识别 (xposedminversion)' '未找到'
        $warnings += $WriteWarn
    }
    if (($foundLegacy -contains 'xposedscope') -and $modern) {
        Write-Check 'INFO' 'xposedscope 元数据' '存在但对现代模块无效（现代模块只读 scope.list）'
    }

    # ── 6. 总判定：复刻 ModuleUtil.reloadInstalledModules ─────────────
    Write-Host ""
    Write-Host "──────── 判定（与 LSPosed ModuleUtil 一致） ────────"
    if ($modern -or $legacy) {
        Write-Host "结果: 该 APK 会被 LSPosed 识别为模块"
        Write-Host ("       识别路径: {0}" -f $(if ($modern) { 'modern (java_init.list)' } else { 'legacy (xposedminversion)' }))
    } else {
        Write-Host "结果: *** LSPosed 不会把这个 APK 当作模块 —— 模块列表里看不到 ***"
        $failures += 'modern 与 legacy 两条识别路径都失败'
    }
    if ($modern -and $legacy) {
        Write-Host "       注: 两条路径同时存在，LSPosed 优先走 modern，manifest 的 xposedscope/xposeddescription 会被忽略"
    }

    if ($warnings.Count) {
        Write-Host ""
        Write-Host "警告:"
        $warnings | ForEach-Object { Write-Host "  - $_" }
    }
    if ($failures.Count) {
        Write-Host ""
        Write-Host "失败:"
        $failures | ForEach-Object { Write-Host "  - $_" }
    }
    Write-Host ""
} finally {
    $zip.Dispose()
}

if ($failures.Count -gt 0) { exit 1 }
if ($Strict -and $warnings.Count -gt 0) { exit 2 }
exit 0
