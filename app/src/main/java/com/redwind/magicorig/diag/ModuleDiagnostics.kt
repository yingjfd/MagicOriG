package com.redwind.magicorig.diag

import android.content.Context
import android.content.pm.PackageManager
import java.util.zip.ZipFile

/**
 * ModuleDiagnostics —— 在手机上直接自检「LSPosed 能不能把本 APK 认成模块」。
 *
 * 判定逻辑逐条复刻 LSPosed 官方管理器：
 *   app/src/main/java/org/lsposed/manager/util/ModuleUtil.java
 *
 *     getModernModuleApk(info)  打开 APK，若存在 META-INF/xposed/java_init.list 则为现代模块
 *     isLegacyModule(info)      info.metaData 含 xposedminversion 则为旧式模块
 *     reloadInstalledModules()  两者满足其一 -> 进入模块列表；都不满足 -> 管理器里完全看不到
 *
 * 因此本类的 [Report.recognized] 与 LSPosed 的实际行为一一对应，可用来定位「看不到模块」。
 */
object ModuleDiagnostics {

    private const val ENTRY_JAVA_INIT = "META-INF/xposed/java_init.list"
    private const val ENTRY_MODULE_PROP = "META-INF/xposed/module.prop"
    private const val ENTRY_SCOPE_LIST = "META-INF/xposed/scope.list"
    private const val ENTRY_NATIVE_INIT = "META-INF/xposed/native_init.list"

    private val LEGACY_KEYS = listOf("xposedminversion", "xposedmodule", "xposeddescription", "xposedscope")

    data class Report(
        val apkPath: String,
        val modern: Boolean,
        val javaInitEntries: List<String>,
        val moduleProp: Map<String, String>,
        val scopeList: List<String>,
        val hasNativeInit: Boolean,
        val legacyKeys: List<String>,
        val providerAuthority: String?
    ) {
        /** 与 LSPosed 相同：现代 或 旧式，满足其一即为模块。 */
        val recognized: Boolean
            get() = modern || legacyKeys.contains("xposedminversion")

        val minApiVersion: Int
            get() = extractIntPart(moduleProp["minApiVersion"])

        /** LSPosed 里 targetVersion 的来源；缺失时该字段为 null（存在 NPE 风险，应显式声明）。 */
        val targetApiVersion: String?
            get() = moduleProp["targetApiVersion"]

        val staticScope: Boolean
            get() = moduleProp["staticScope"].equals("true", ignoreCase = true)

        val hasModuleProp: Boolean get() = moduleProp.isNotEmpty()

        val scopeSource: String
            get() = when {
                modern && scopeList.isNotEmpty() -> "scope.list（${scopeList.size} 项）"
                modern -> "无 —— 现代模块只读 scope.list，manifest 的 xposedscope 会被忽略"
                else -> "manifest xposedscope 元数据"
            }

        /** 逐项诊断，供 UI 展示。 */
        fun checks(): List<Check> = buildList {
            add(
                if (modern) Check("现代识别 · META-INF/xposed/java_init.list", true, javaInitEntries.joinToString())
                else Check("现代识别 · META-INF/xposed/java_init.list", false, "APK 内找不到该条目")
            )
            add(
                if (legacyKeys.contains("xposedminversion")) Check("旧式识别 · xposedminversion 元数据", true, legacyKeys.joinToString())
                else Check("旧式识别 · xposedminversion 元数据", false, "manifest 中未声明")
            )
            add(
                if (recognized) Check("LSPosed 是否识别为模块", true, if (modern) "modern" else "legacy")
                else Check("LSPosed 是否识别为模块", false, "两条路径都失败 —— 管理器里不会出现")
            )
            add(
                if (hasModuleProp) Check("module.prop", true, null)
                else Check("module.prop", false, "缺失，将退回 min=100/target=100/staticScope=false")
            )
            add(
                if (moduleProp.containsKey("minApiVersion")) Check("minApiVersion", true, moduleProp["minApiVersion"])
                else Check("minApiVersion", false, "缺失")
            )
            add(
                if (targetApiVersion != null) Check("targetApiVersion", true, targetApiVersion)
                else Check("targetApiVersion", false, "缺失 —— 建议显式声明，规避 LSPosed 的空值风险")
            )
            add(Check("staticScope", null, if (staticScope) "true（静态作用域）" else "false（动态作用域）"))
            add(
                if (modern) {
                    if (scopeList.isNotEmpty()) Check("作用域来源", true, scopeSource)
                    else Check("作用域来源", false, "scope.list 缺失或为空 —— 作用域为空，模块不会生效")
                } else Check("作用域来源", true, scopeSource)
            )
            add(
                if (providerAuthority != null) Check("XposedProvider", true, providerAuthority)
                else Check(
                    "XposedProvider",
                    false,
                    "未声明 <applicationId>.XposedService —— libxposed service 常规依赖此 provider，" +
                        "缺失时模块设置页的 getRemotePreferences() 可能拿不到远程配置"
                )
            )
        }
    }

    data class Check(val label: String, val ok: Boolean?, val detail: String?)

    fun collect(context: Context): Report = runCatching {
        val apkPath = context.applicationInfo.sourceDir
        var modern = false
        var javaInitEntries: List<String> = emptyList()
        var moduleProp: Map<String, String> = emptyMap()
        var scopeList: List<String> = emptyList()
        var hasNativeInit = false

        runCatching {
            ZipFile(apkPath).use { zip ->
                zip.getEntry(ENTRY_JAVA_INIT)?.let { entry ->
                    modern = true
                    javaInitEntries = readLines(zip, entry).filter { it.isNotBlank() }
                }
                zip.getEntry(ENTRY_MODULE_PROP)?.let { entry ->
                    moduleProp = readProperties(readText(zip, entry))
                }
                zip.getEntry(ENTRY_SCOPE_LIST)?.let { entry ->
                    scopeList = readLines(zip, entry).filter { it.isNotBlank() }
                }
                hasNativeInit = zip.getEntry(ENTRY_NATIVE_INIT) != null
            }
        }.onFailure { /* APK 打不开时保留 modern=false，下面会走失败分支 */ }

        val legacyKeys = runCatching {
            val metaData = context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
            LEGACY_KEYS.filter { metaData?.containsKey(it) == true }
        }.getOrDefault(emptyList())

        val providerAuthority = runCatching {
            val info = context.packageManager.resolveContentProvider(
                "${context.packageName}.XposedService", 0
            )
            info?.authority
        }.getOrNull()

        Report(
            apkPath = apkPath,
            modern = modern,
            javaInitEntries = javaInitEntries,
            moduleProp = moduleProp,
            scopeList = scopeList,
            hasNativeInit = hasNativeInit,
            legacyKeys = legacyKeys,
            providerAuthority = providerAuthority
        )
    }.getOrElse { error ->
        Report(
            apkPath = "读取失败: ${error.message}",
            modern = false,
            javaInitEntries = emptyList(),
            moduleProp = emptyMap(),
            scopeList = emptyList(),
            hasNativeInit = false,
            legacyKeys = emptyList(),
            providerAuthority = null
        )
    }

    private fun readText(zip: ZipFile, entry: java.util.zip.ZipEntry): String =
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun readLines(zip: ZipFile, entry: java.util.zip.ZipEntry): List<String> =
        readText(zip, entry).split('\n').map { it.trimEnd('\r') }

    private fun readProperties(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            out[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
        }
        return out
    }
}

/**
 * 与 LSPosed `ModuleUtil.extractIntPart` 等价的「取前导数字」逻辑，但做了空值保护。
 *
 * LSPosed 原实现是 `str.length()`，传 null 会直接 NPE —— 这正是本模块原先
 * 在 LSPosed 里「一个模块都看不到」的根因。此处保持语义一致、仅补上 null 判断。
 */
private fun extractIntPart(raw: String?): Int {
    if (raw == null) return 0
    var result = 0
    for (c in raw) {
        if (c in '0'..'9') result = result * 10 + (c - '0') else break
    }
    return result
}
