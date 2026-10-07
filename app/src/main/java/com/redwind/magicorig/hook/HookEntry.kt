package com.redwind.magicorig.hook

import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import com.redwind.magicorig.config.ConfigManager

/**
 * HookEntry — 模块入口点。
 *
 * 基于 HyperOriG/HookEntry.kt 适配：
 * - 移除 com.milink.service（MagicOS 无此服务）
 * - 移除 com.xiaomi.bluetooth（MagicOS 无此包）
 * - 添加 com.huawei.android.bluetooth（MagicOS 蓝牙服务，待真机验证类名）
 * - 保留 com.android.bluetooth、com.android.systemui、com.android.settings
 */
class HookEntry : XposedModule() {
    private val TAG = "MagicOriG-HookEntry"

    private val configListeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return

        // 必须最先绑定 Log.module：Log.* 走的是 module?.log(...)，
        // 而原先 module 只在 loadHook 内部才赋值，导致 onPackageReady 里的
        // 「ENTRY LOADED」永远因为 module==null 被静默丢弃。
        Log.module = this

        // 无条件入口日志 —— 必须走模块自己的 Log（module.log → LSPosed 日志文件）。
        // 实测 android.util.Log 在本机 LSPosed 注入进程里完全不进 logcat（buffer 回溯到 17:05，
        // 17:18 的调用却一条都没有），只有 module.log() 这条通道可靠。
        // 同时不加 logLevel 门控的判断，靠 Log.i 默认级别（>=BASIC）保证可见。
        Log.i(
            TAG,
            "ENTRY LOADED pkg=${param.packageName} first=${param.isFirstPackage}"
        )

        when (param.packageName) {
            // ── SystemUI（标准 Android）──────────────────────────────────
            "com.android.systemui" -> {
                loadHook(SystemUIHeadsetIconHook, param.classLoader, param.packageName)
            }

            // ── 蓝牙服务（标准 Android）── 核心 Hook 入口 ────────────────
            "com.android.bluetooth" -> {
                loadHook(HeadsetStateDispatcher, param.classLoader, param.packageName)
                loadHook(BluetoothUpstreamHeadsetHook, param.classLoader, param.packageName)
            }

            // ── 系统设置 ───────────────────────────────────────────────
            "com.android.settings" -> {
                loadHook(SettingsHeadsetHook, param.classLoader, param.packageName)
            }

            // ── 荣耀耳机面板（电量 / 降噪 UI 所在进程）────────────────
            "com.hihonor.audioaccessorymanager" -> {
                loadHook(HonorEarphoneHook, param.classLoader, param.packageName)
            }
        }
    }

    private fun loadHook(
        hook: HookContext,
        classLoader: ClassLoader,
        packageName: String
    ) {
        Log.module = this
        hook.module = this
        hook.appClassLoader = classLoader
        hook.packageName = packageName
        try {
            hook.prefs = getRemotePreferences("magicorig_settings")
            Log.i(TAG, "loadHook pkg=$packageName hook=${hook.javaClass.simpleName}")
            ConfigManager.init(hook.prefs)
            val configListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
                if (key == ConfigManager.PREF_KEY_CONFIG_JSON) {
                    ConfigManager.refreshFromPrefs(prefs)
                }
            }
            configListeners.add(configListener)
            hook.prefs.registerOnSharedPreferenceChangeListener(configListener)
            hook.onHook()
            Log.i(TAG, "onHook OK pkg=$packageName hook=${hook.javaClass.simpleName}")
        } catch (error: Throwable) {
            // 失败必须能看见：原先 log() 受 logLevel 门控，级别调低时错误会被吞
            Log.e(TAG, "FAILED init $packageName/${hook.javaClass.simpleName}")
            log(android.util.Log.ERROR, TAG, "Failed to initialize $packageName/${hook.javaClass.simpleName}", error)
        }
    }
}
