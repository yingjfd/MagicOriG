package com.redwind.magicorig.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import com.redwind.magicorig.pods.RfcommController
import com.redwind.magicorig.utils.SystemApisUtils.setIconVisibility
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * HeadsetStateDispatcher — A2DP 连接状态监听。
 *
 * 防炸机要点：
 * 1. instance 强转为 ContextWrapper 可能在某些 Android 版本崩溃，用 runCatching
 * 2. RfcommController.connectPod 在子线程执行，不在 handler 的 post 内直接调用
 * 3. 用 AtomicBoolean 防止并发 connect/disconnect 导致 RFCOMM 状态混乱
 * 4. 每次操作前检查 isConnected 状态，避免断连后仍发操作
 * 5. StatusBarManager 获取用懒初始化 + 缓存
 */
@SuppressLint("MissingPermission")
object HeadsetStateDispatcher : HookContext() {
    private const val TAG = "MagicOriG-Dispatcher"

    // ── 线程安全状态 ────────────────────────────────────────────
    private val isConnecting = AtomicBoolean(false)
    private var cachedContext: android.content.Context? = null
    private var cachedStatusBarManager: StatusBarManager? = null
    private var statusBarInitialized = false

    override fun onHook() {
        // hook A2dpService.handleConnectionStateChanged — 蓝牙连接核心
        runCatching {
            hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
                safeHandleConnectionStateChanged(this)
            }
            Log.d(TAG, "A2dpService.handleConnectionStateChanged hook installed")
        }.onFailure { e ->
            // 关键：这个 hook 失败不应该导致模块整体崩溃
            Log.e(TAG, "failed to hook handleConnectionStateChanged, trying alternative", e)
            tryAlternativeHook()
        }

        // ── 兜底：直接监听系统 A2DP 广播 ────────────────────────────
        // 实测 23:49:17 A2DP 实际 STATE_CONNECTED，但 handleConnectionStateChanged 未被回调，
        // 导致 safeConnect → connectPod 永不执行（SPP 始终未建立）。
        // 改用系统广播作为第二触发源，确保连接后一定能走 connectPod。
        runCatching {
            // onHook 运行在 Application 创建之前 → currentApplication 为 null（真机已踩）。
            // 改为丢到主循环延迟注册。
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching {
                    val app = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                        ?: throw IllegalStateException("currentApplication null")
                    val filter = android.content.IntentFilter(
                        "android.bluetooth.a2dp.profile.connection.action.CONNECTION_STATE_CHANGED"
                    )
            val rc = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                    try {
                        val dev: android.bluetooth.BluetoothDevice? =
                            intent?.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                        val state = intent?.getIntExtra(
                            android.bluetooth.BluetoothProfile.EXTRA_STATE, -1) ?: -1
                        val sPrev = intent?.getIntExtra(
                            android.bluetooth.BluetoothProfile.EXTRA_PREVIOUS_STATE, -1) ?: -1
                        if (dev == null) return
                        Log.i(TAG, "A2DP 广播 state=$state(prev=$sPrev) dev=${dev.address}")
                        if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED &&
                            isOriGPod(dev)
                        ) {
                            Log.i(TAG, "★ A2DP 广播 CONNECTED → 触发 connectPod")
                            safeConnect(ctx ?: app, dev)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "A2DP 广播处理失败", e)
                    }
                }
            }
            app.registerReceiver(rc, filter, android.content.Context.RECEIVER_EXPORTED)
                Log.i(TAG, "★ A2DP 系统广播 receiver 已注册（connectPod 兜底）")
                }.onFailure {
                    Log.w(TAG, "注册 A2DP 广播失败: ${it.javaClass.simpleName}: ${it.message}")
                }
            }, 3000L)
        }.onFailure {
            Log.w(TAG, "postDelayed 注册失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun tryAlternativeHook() {
        // 备用方案：hook connect() 方法
        runCatching {
            val a2dpClass = findClass("com.android.bluetooth.a2dp.BluetoothA2dp")
            hookBefore(a2dpClass.method("connect", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOriGPod(device)) return@hookBefore
                Log.d(TAG, "A2dpService.connect intercepted device=${device.address}")
            }
            Log.d(TAG, "Alternative hook (connect) installed")
        }.onFailure { Log.e(TAG, "alternative hook also failed", it) }
    }

    private fun safeHandleConnectionStateChanged(param: HookParam) {
        try {
            val currState = param.args[2] as? Int ?: return
            val fromState = param.args[1] as? Int ?: return
            val device = param.args[0] as? BluetoothDevice ?: return

            // 状态未变化，跳过
            if (currState == fromState) return

            // 非 OriG in 设备，快速跳过
            if (!isOriGPod(device)) return

            Log.d(TAG, "A2DP state: $currState (was $fromState), device=${device.address}")

            // 获取 context
            // MagicOS 的 A2dpService 继承链 A2dpService → ConnectableProfile → ProfileService
            // 里根本没有 mContext 字段（ConnectableProfile 只有 storage），
            // 原先 getObjectField("mContext") 必然抛异常 → "mContext not available" → RFCOMM 永远连不上。
            // 但 A2dpService 是 Service → ContextWrapper，实例本身就是 Context，直接用即可。
            val context = (param.instance as? ContextWrapper)
                ?: runCatching { getObjectField(param.instance, "mContext") as? ContextWrapper }.getOrNull()
                ?: runCatching { getObjectField(param.instance, "mBase") as? ContextWrapper }.getOrNull()

            if (context == null) {
                Log.e(TAG, "context unavailable: instance=${param.instance?.javaClass?.name}")
                return
            }

            // 缓存 context 和 StatusBarManager
            cachedContext = context.applicationContext ?: context
            if (!statusBarInitialized) {
                cachedStatusBarManager = runCatching {
                    cachedContext!!.getSystemService(StatusBarManager::class.java)
                }.getOrNull()
                statusBarInitialized = true
            }

            // 所有图标和 RFCOMM 操作在 main handler 上执行
            val handler = runCatching { getObjectField(param.instance, "mHandler") as? Handler }.getOrNull()
                ?: Handler(Looper.getMainLooper())

            handler.post { safeProcessStateChange(context, device, currState) }
        } catch (e: Throwable) {
            Log.e(TAG, "handleConnectionStateChanged hook threw", e)
        }
    }

    private fun safeProcessStateChange(context: Context, device: BluetoothDevice, state: Int) {
        // 无条件成功日志：走到这里说明 context 已经拿到（原先 mContext 修复是否生效的唯一确认点）
        // 走 module.log() —— 实测 android.util.Log 在注入进程里不进 logcat
        Log.i(
            TAG,
            "stateChange state=$state(${stateName(state)}) dev=${device.address} ctx=${context.javaClass.simpleName}"
        )
        try {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // 任务6 连接态信号：A2DP/HEADSET 连上即写 1（比 SPP 电量回调更早更可靠，
                    // 电量回调在回连后可能长时间不触发 → 球门控会误杀）
                    runCatching {
                        android.provider.Settings.Global.putString(
                            context.contentResolver, "magicorig_connected", "1"
                        )
                        Log.i(TAG, "connected=1 (stateChange ${device.address})")
                    }
                    if (isConnecting.compareAndSet(false, true)) {
                        thread(name = "magicorig-rfcomm-connect") {
                            safeConnect(context, device)
                        }
                    } else {
                        Log.d(TAG, "connect already in progress, skipping")
                    }
                    // 图标不在此处更新：
                    // StatusBarManager.setIconVisibility 需要 STATUS_BAR_SERVICE 权限，
                    // 只有 SystemUI 持有；在 com.android.bluetooth 进程里调用会被
                    // StatusBarManagerService.enforceStatusBar 拒绝（真机实测 RemoteException）。
                    // 图标统一由 SystemUIHeadsetIconHook 在 SystemUI 进程内处理。
                    Log.i(TAG, "icon deferred to SystemUI hook (dev=${device.address})")
                }

                BluetoothProfile.STATE_DISCONNECTING, BluetoothProfile.STATE_DISCONNECTED -> {
                    // 任务6 连接态信号：断连写 0（与 RfcommController.disconnectedPod 双保险）
                    runCatching {
                        android.provider.Settings.Global.putString(
                            context.contentResolver, "magicorig_connected", "0"
                        )
                        Log.i(TAG, "connected=0 (stateChange ${device.address})")
                    }
                    isConnecting.set(false)
                    // 图标同样交给 SystemUI hook（见 CONNECTED 分支说明）
                    Log.i(TAG, "icon deferred to SystemUI hook (disconnected, dev=${device.address})")
                    // RFCOMM 断连
                    val ctx = cachedContext ?: context
                    runCatching {
                        RfcommController.disconnectedPod(ctx, device)
                    }.onFailure { Log.e(TAG, "disconnectedPod failed", it) }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "safeProcessStateChange failed for state=$state", e)
        }
    }

    private fun safeConnect(context: Context, device: BluetoothDevice) {
        try {
            RfcommController.connectPod(context, device, prefs)
            Log.i(TAG, "RFCOMM connect initiated for ${device.address}")
        } catch (e: Throwable) {
            Log.e(TAG, "RFCOMM connectPod threw exception", e)
        } finally {
            isConnecting.set(false)
        }
    }

    private fun stateName(state: Int): String = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN"
    }

    /**
     * 判断是否为 OriG in 耳机 — 纯字符串匹配，无任何反射，绝对安全
     */
    @SuppressLint("MissingPermission")
    fun isOriGPod(device: BluetoothDevice): Boolean {
        return try {
            val name = device.name ?: return false
            val alias = try { device.alias } catch (e: Throwable) { null } ?: ""
            val combined = "$name $alias"
            combined.contains("YUANDAO", ignoreCase = true) ||
            combined.contains("OriG", ignoreCase = true) ||
            combined.contains("NiceHCK", ignoreCase = true)
        } catch (e: Throwable) {
            Log.w(TAG, "isOriGPod check failed for ${device.address}", e)
            false
        }
    }
}
