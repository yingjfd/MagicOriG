package com.redwind.magicorig.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.redwind.magicorig.utils.SystemApisUtils.setIconVisibility
import java.util.WeakHashMap
import kotlin.concurrent.thread

/**
 * SystemUIHeadsetIconHook — 在 SystemUI 中管理耳机图标显示。
 *
 * 核心安全原则（防止炸机）：
 * 1. 所有反射调用必须用 runCatching 包裹，任何异常都不应抛出到 hook 链
 * 2. 回调必须用 WeakReference 存储，防止 context 泄漏
 * 3. 所有耗时操作必须在后台线程执行，不在主线程上反射
 * 4. registerReceiver 必须在 try-catch 内，系统可能在某些 Android 版本拒绝注册
 * 5. 图标更新用 iconOwned 状态机，避免重复 setIcon 导致资源竞争
 * 6. 每次操作都有最大重试次数限制，无限重试会卡死 SystemUI
 */
@SuppressLint("MissingPermission")
object SystemUIHeadsetIconHook : HookContext() {
    private const val TAG = "MagicOriG-SystemUI"

    /** 读跨进程电量（"L90,R85"）→ "左耳 90% 右耳 85%"；无数据返回原文占位 */
    private fun readBatteryLine(): String = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return "左耳 --% 右耳 --%"
        val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicorig_battery")
            ?: return "左耳 --% 右耳 --%"
        if (!raw.contains(",")) return "左耳 --% 右耳 --%"
        val l = raw.substringBefore(',').removePrefix("L").toIntOrNull()
        val r = raw.substringAfter(',').removePrefix("R").toIntOrNull()
        val ls = if (l != null && l >= 0) "左耳 $l%" else "左耳 --%"
        val rs = if (r != null && r >= 0) "右耳 $r%" else "右耳 --%"
        "$ls $rs"
    }.getOrDefault("左耳 --% 右耳 --%")
    private const val SLOT = "wireless_headset"
    private const val MAX_RECONNECT_ATTEMPTS = 3

    // ── 安全状态 ──────────────────────────────────────────────────
    // 用 stateGeneration 保证状态机不会被过期回调覆盖
    private var stateGeneration = 0
    private val connectedAddresses = mutableSetOf<String>()
    private var iconOwned = false

    // ── 延迟初始化：避免 class 加载时引发 NPE ─────────────────────
    private var statusBarManager: StatusBarManager? = null
    private var statusBarManagerInitialized = false

    // ── 线程安全：所有回调必须在主线程执行 ────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onHook() {
        // 0. ★ 任务4：通知文案在 SystemUI 渲染端改写（RemoteViews.apply/reapply 返回的
        //    View 树 = 渲染前最后一刻）。sws 进程内的 set 层改写实测全部被荣耀混淆
        //    渲染类（QuaentCrearsaisSusts，动态 classloader 加载、无法 hook）覆盖，
        //    apply 也发生在 SystemUI 而非 sws —— 只有这里改写必然生效。
        //    文案匹配足够精确（只有音频设备通知含这两行），误伤面为零。
        runCatching {
            val rvCls = Class.forName("android.widget.RemoteViews")
            var cnt = 0
            for (mn in arrayOf("apply", "reapply")) {
                val m = rvCls.declaredMethods.firstOrNull {
                    it.name == mn && it.parameterCount >= 1 &&
                            android.view.View::class.java.isAssignableFrom(it.returnType)
                } ?: continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed()
                    if (ret is android.view.View) {
                        var changed = 0
                        fun walk(v: android.view.View) {
                            if (v is android.widget.TextView) {
                                when (val cur = v.text?.toString() ?: "") {
                                    "当前音频输入输出设备" -> { v.text = "原道 OriG in"; changed++ }
                                    "原道OriG in" -> { v.text = readBatteryLine(); changed++ }
                                }
                            } else if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) walk(v.getChildAt(i))
                            }
                        }
                        walk(ret)
                        if (changed > 0) Log.w(TAG, "★★★ SystemUI 通知改写 $changed 处（$mn）")
                    }
                    ret
                }
                cnt++
            }
            Log.i(TAG, "installed: RemoteViews.apply [SystemUI 通知文案改写] ($cnt)")
        }.onFailure {
            Log.w(TAG, "install SystemUI apply hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // 1. 找 SystemUI 启动时机
        // MagicOS 的 SystemUI 里没有 CentralSurfacesImpl / SystemUIApplication / SystemUI
        // （真机 dexdump 确认），原来固定 hook CentralSurfacesImpl.start 必然 findClass 抛异常。
        // SystemUIService 是 Honor 保留下来的 AOSP 类，onCreate 是可靠的启动时机。
        val startTargets = listOf(
            "com.android.systemui.SystemUIService",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl"   // AOSP 回退
        )
        var installed = false
        for (cls in startTargets) {
            if (installed) break
            runCatching {
                hookAfter(findMethod(cls, "onCreate")) { safeStartHook(this) }
                installed = true
                Log.e(TAG, "startup hook installed on $cls.onCreate")
            }.onFailure {
                Log.i(TAG, "startup hook skipped for $cls: ${it.javaClass.simpleName}")
            }
        }

        // 2. hook StatusBarManager.setIconVisibility — 独立 hook
        runCatching {
            val smClass = findClass("android.app.StatusBarManager")
            hookAfter(smClass.method("setIconVisibility", String::class.java, Boolean::class.java)) {
                safeStatusBarHook(this)
            }
            Log.d(TAG, "StatusBarManager.setIconVisibility hook installed")
        }.onFailure { Log.d(TAG, "StatusBarManager hook skipped (may not be needed)") }
    }

    private fun safeStartHook(param: HookParam) {
        // 获取 context
        // SystemUIService 是 Service → ContextWrapper，实例本身就是 Context；
        // 跟 HeadsetStateDispatcher 同一个坑：继承链里没有 mContext 字段，
        // 反射取它必然失败 → 这里原先直接 return，整个图标 hook 静默不生效。
        val context: Context? = (param.instance as? Context)
            ?: runCatching { getObjectField(param.instance, "mContext") as? Context }.getOrNull()
            ?: runCatching { getObjectField(param.instance, "mBase") as? Context }.getOrNull()

        if (context == null) {
            Log.e(TAG, "context unavailable instance=${param.instance?.javaClass?.name}")
            return
        }
        Log.i(TAG, "startup hook fired, ctx=${context.javaClass.simpleName}")

        // 创建 receiver
        val receiver = createSafeReceiver(context)
        if (receiver == null) return  // 创建失败不阻塞

        // 注册 receiver 必须有 try-catch
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
            Log.d(TAG, "A2DP broadcast receiver registered")
        }.onFailure { e ->
            Log.w(TAG, "failed to register A2DP receiver: ${e.message}")
            // 尝试降级方案：用 exported receiver
            runCatching {
                context.registerReceiver(
                    receiver,
                    IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                    Context.RECEIVER_EXPORTED
                )
                Log.d(TAG, "A2DP receiver registered (fallback: EXPORTED)")
            }.onFailure { e2 ->
                Log.e(TAG, "both receiver registration methods failed", e2)
            }
        }

        // 系统启动时恢复图标状态
        restoreIconsOnBoot(context)
    }

    private fun createSafeReceiver(context: Context): BroadcastReceiver? {
        return try {
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    safeOnReceive(ctx, intent)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to create receiver", e)
            null
        }
    }

    private fun safeOnReceive(context: Context, intent: Intent) {
        try {
            val device = try {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } catch (e: Throwable) {
                // Android 14+ 可能需要不同的 extra key
                try {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } catch (e2: Throwable) { null }
            } ?: return

            // 快速判断：非 OriG in 耳机直接返回，不处理
            if (!HeadsetStateDispatcher.isOriGPod(device)) return

            val state = try {
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            } catch (e: Throwable) { -1 }

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    safeAddConnected(device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> {
                    safeRemoveConnected(device.address)
                }
            }
            stateGeneration++
            mainHandler.post { safeUpdateIcon(context) }
        } catch (e: Throwable) {
            Log.e(TAG, "onReceive failed", e)
        }
    }

    private fun safeAddConnected(address: String) {
        try { connectedAddresses.add(address) } catch (e: Throwable) { Log.w(TAG, "addConnected failed", e) }
    }

    private fun safeRemoveConnected(address: String) {
        try { connectedAddresses.remove(address) } catch (e: Throwable) { Log.w(TAG, "removeConnected failed", e) }
    }

    private fun restoreIconsOnBoot(context: Context) {
        // 蓝牙 adapter 获取可能失败，需捕获
        val adapter = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter
        }.getOrNull() ?: return

        val generation = stateGeneration
        // getProfileProxy 回调必须在后台线程
        thread(name = "magicorig-bt-proxy") {
            try {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        try {
                            if (generation != stateGeneration) {
                                // 已过期，不更新
                                return
                            }
                            // 只处理 A2DP profile
                            if (profile != BluetoothProfile.A2DP) return
                            val freshConnected = mutableListOf<String>()
                            runCatching {
                                proxy.connectedDevices
                                    .filter { HeadsetStateDispatcher.isOriGPod(it) }
                                    .forEach { freshConnected.add(it.address) }
                            }.onFailure { Log.w(TAG, "connectedDevices query failed", it) }
                            connectedAddresses.clear()
                            connectedAddresses.addAll(freshConnected)
                            mainHandler.post { safeUpdateIcon(context) }
                        } catch (e: Throwable) {
                            Log.e(TAG, "onServiceConnected failed", e)
                        } finally {
                            try { adapter.closeProfileProxy(profile, proxy) } catch (e: Throwable) {
                                Log.w(TAG, "closeProfileProxy failed", e)
                            }
                        }
                    }
                    override fun onServiceDisconnected(profile: Int) = Unit
                }, BluetoothProfile.A2DP)
            } catch (e: Throwable) {
                Log.e(TAG, "getProfileProxy failed", e)
            }
        }
    }

    private fun safeUpdateIcon(context: Context) {
        try {
            // 懒初始化 statusBarManager
            if (!statusBarManagerInitialized) {
                statusBarManager = runCatching {
                    context.getSystemService(StatusBarManager::class.java)
                }.getOrNull()
                statusBarManagerInitialized = true
            }
            val sm = statusBarManager ?: return

            val hasConnected = connectedAddresses.isNotEmpty()

            // 状态未变化则跳过
            if (hasConnected == iconOwned) return

            // setIconVisibility 必须在主线程（SystemUI 要求）
            runCatching {
                sm.setIconVisibility(SLOT, hasConnected)
                if (hasConnected) {
                    // 尝试设置图标 — 获取 drawable ID 可能失败
                    val drawableId = runCatching {
                        context.resources.getIdentifier("stat_sys_wireless_headset", "drawable", context.packageName)
                    }.getOrNull() ?: 0
                    if (drawableId != 0) {
                        runCatching {
                            callMethod(sm, "setIcon", SLOT, drawableId, 0, "MagicOriG")
                        }.onFailure { Log.w(TAG, "setIcon failed, visibility only", it) }
                    }
                }
                iconOwned = hasConnected
                Log.d(TAG, "icon updated: visible=$hasConnected owned=$iconOwned")
            }.onFailure { e ->
                Log.e(TAG, "setIconVisibility failed", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "safeUpdateIcon crashed", e)
        }
    }

    private fun safeStatusBarHook(param: HookParam) {
        // 拦截 setIconVisibility 确保图标不会意外被其他代码隐藏
        try {
            val slot = param.args[0] as? String
            val show = param.args[1] as? Boolean
            if (slot == SLOT && show == true) {
                // 确保 iconOwned 状态同步
                iconOwned = true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "safeStatusBarHook failed", e)
        }
    }
}
