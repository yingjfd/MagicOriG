package com.redwind.magicorig.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.redwind.magicorig.config.ConfigManager
import com.redwind.magicorig.pods.RfcommController
import com.redwind.magicorig.utils.MagicOriGAction
import com.redwind.magicorig.utils.huaweiStrongToast.data.BatteryParams
import com.redwind.magicorig.utils.huaweiStrongToast.data.PodParams
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BluetoothUpstreamHeadsetHook — 拦截蓝牙耳机服务 Binder 调用。
 *
 * 防炸机要点（针对 com.android.bluetooth 进程）：
 * 1. 所有 hook install 用独立 try-catch，单个 hook 失败不影响其他
 * 2. callback 用 AtomicBoolean 防止并发 register/unregister 导致 state 混乱
 * 3. handler.post 前检查 looper 是否存活，避免 post 到死线程
 * 4. broadcast 发送用 setPackage() 限定目标，不被其他 app 截获
 * 5. 反射调用全部 runCatching，任何 ClassNotFoundException 只记录不崩溃
 */
@SuppressLint("MissingPermission")
object BluetoothUpstreamHeadsetHook : HookContext() {
    private const val TAG = "MagicOriG-Upstream"
    private val knownAddresses = linkedSetOf<String>()
    private val callbacks = mutableMapOf<IBinder, Any>()
    private val handler = Handler(Looper.getMainLooper())
    private val hookedBinderClasses = linkedSetOf<String>()
    private var lastOriGDevice: BluetoothDevice? = null
    private var context: Context? = null
    private var receiverRegistered = false
    private var currentBattery: BatteryParams? = null
    private var currentAnc = 1
    private var currentAddress: String? = null
    private var currentName: String? = null

    // 防止并发状态更新
    private val isUpdatingState = AtomicBoolean(false)

    override fun onHook() {
        // 分步骤 hook，单步失败不影响后续
        runCatching { hookHeadsetServiceBinder() }
            .onFailure { Log.e(TAG, "hookHeadsetServiceBinder failed", it) }
        runCatching { hookNotificationBattery() }
            .onFailure { Log.d(TAG, "hookNotificationBattery skipped (may not exist on MagicOS)") }
    }

    private fun hookNotificationBattery() {
        // MiuiBluetoothNotificationApi — HyperOS 特有，MagicOS 可能不存在
        val notificationApiClass = findClassOrNull("com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi")
        if (notificationApiClass != null) {
            runCatching {
                hookBefore(notificationApiClass.method("showNewConnectedToast",
                    Int::class.java, Int::class.java, Int::class.java, Int::class.java,
                    BluetoothDevice::class.java, String::class.java)) {
                    val device = args[4] as? BluetoothDevice ?: return@hookBefore
                    if (!isOriGPod(device)) return@hookBefore
                    val battery = effectiveBattery() ?: return@hookBefore
                    val leftBattery = displayBattery(battery.left) ?: (args[1] as? Int ?: 0)
                    val rightBattery = displayBattery(battery.right) ?: (args[2] as? Int ?: 0)
                    val wearState = displayWearState(battery, args[3] as? Int ?: 1)
                    val notification = currentMiuiNotification() ?: return@hookBefore
                    result = null
                    callMethod(notification, "showConnectedToast",
                        args[0] as? Int ?: 2, leftBattery, rightBattery, wearState, device, args[5] as? String)
                    Log.d(TAG, "showNewConnectedToast patched left=$leftBattery right=$rightBattery wear=$wearState")
                }
                Log.d(TAG, "MiuiBluetoothNotificationApi hook installed (HyperOS fallback)")
            }.onFailure { Log.d(TAG, "MiuiBluetoothNotificationApi hook skipped") }
        }

        // MiuiBluetoothNotification.updateParameters — 灵动岛/焦点通知
        val notificationClass = findClassOrNull("com.android.bluetooth.ble.app.MiuiBluetoothNotification")
        val updateParameters = notificationClass?.declaredMethods?.singleOrNull {
            it.name == "updateParameters" && it.parameterCount == 1
        }?.apply { isAccessible = true }
        val requestClass = updateParameters?.parameterTypes?.singleOrNull()
        if (notificationClass != null && updateParameters != null && requestClass != null) {
            runCatching {
                fun requestField(vararg names: String) = names.firstNotNullOfOrNull { name ->
                    runCatching { requestClass.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
                } ?: throw NoSuchFieldException(names.joinToString())
                val deviceField = requestField("e", "f18110e")
                val leftField = requestField("b", "f18107b")
                val rightField = requestField("c", "f18108c")
                val wearField = requestField("d", "f18109d")
                check(deviceField.type == BluetoothDevice::class.java)
                check(listOf(leftField, rightField, wearField).all { it.type == Int::class.javaPrimitiveType })
                hookAfter(updateParameters) {
                    val request = args[0] ?: return@hookAfter
                    val device = deviceField.get(request) as? BluetoothDevice ?: return@hookAfter
                    if (!isOriGPod(device)) return@hookAfter
                    val battery = effectiveBattery() ?: return@hookAfter
                    leftField.setInt(request, displayBattery(battery.left) ?: 0)
                    rightField.setInt(request, displayBattery(battery.right) ?: 0)
                    wearField.setInt(request, displayWearState(battery, wearField.get(request) as? Int ?: 1))
                    Log.d(TAG, "updateParameters patched device=${device.address}")
                }
                Log.d(TAG, "MiuiBluetoothNotification.updateParameters hook installed")
            }.onFailure { Log.d(TAG, "MiuiBluetoothNotification hook skipped: ${it.message}") }
        }
    }

    private fun hookHeadsetServiceBinder() {
        val serviceClass = findClassOrNull("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService")
        if (serviceClass != null) {
            // hook onBind — 获取 binder 实例
            runCatching {
                hookAfter(serviceClass.method("onBind", Intent::class.java)) {
                    registerStatusReceiver(instance as? Context)
                    val binder = result ?: return@hookAfter
                    safeInstallBinderHooks(binder.javaClass)
                }
                Log.d(TAG, "BluetoothHeadsetService.onBind hook installed")
            }.onFailure { Log.w(TAG, "onBind hook failed", it) }

            // hook onCreate — 仅用于注册 receiver
            runCatching {
                hookAfter(serviceClass.method("onCreate")) {
                    registerStatusReceiver(instance as? Context)
                }
            }.onFailure { Log.d(TAG, "onCreate hook skipped") }
        } else {
            Log.d(TAG, "BluetoothHeadsetService not found — MagicOS may use different class")
        }

        // 尝试混淆后的类名
        listOf(
            "com.android.bluetooth.ble.app.headset.BinderC6776v",
            "com.android.bluetooth.ble.app.headset.v"
        ).forEach { className ->
            findClassOrNull(className)?.let { safeInstallBinderHooks(it) }
        }
    }

    private fun safeInstallBinderHooks(binderClass: Class<*>) {
        val className = binderClass.name
        if (!hookedBinderClasses.add(className)) return
        Log.d(TAG, "Installing hooks for $className")

        // checkSupport → 强制返回支持
        hookBeforeSafe(binderClass, "checkSupport", arrayOf(BluetoothDevice::class.java)) {
            val device = args[0] as? BluetoothDevice ?: return@hookBeforeSafe
            if (!isOriGPod(device)) return@hookBeforeSafe
            lastOriGDevice = device; result = fakeSupport()
            Log.d(TAG, "checkSupport forced device=${device.address}")
        }

        // getDeviceInfo
        hookAddressStringResultSafe(binderClass, "getDeviceInfo", "getDeviceInfo") { fakeSupport() }

        // isSupportAudioSwitch
        hookAddressStringResultSafe(binderClass, "isSupportAudioSwitch", "isSupportAudioSwitch") { "1" }

        // isMiTWS / isYuanDaoTWS
        hookAddressBooleanResultSafe(binderClass, listOf("isMiTWS", "isYuanDaoTWS"), "isMiTWS", true)
        hookAddressBooleanResultSafe(binderClass, listOf("checkIsMiTWS", "checkIsYuanDaoTWS"), "checkIsMiTWS", true)

        // getRingFindState
        hookAddressBooleanResultSafe(binderClass, listOf("getRingFindState"), "getRingFindState", false)

        // setCommonCommand
        hookBeforeSafe(binderClass, "setCommonCommand", arrayOf(Int::class.java, String::class.java, BluetoothDevice::class.java)) {
            val command = args[0] as? Int
            val device = args[2] as? BluetoothDevice ?: return@hookBeforeSafe
            if (!isOriGPod(device)) return@hookBeforeSafe
            lastOriGDevice = device; result = "1"
            Log.d(TAG, "setCommonCommand forced cmd=$command device=${device.address}")
            sendRealStatus(device, "setCommonCommand:$command")
        }

        // connect / getDeviceConfig
        hookBinderVoidDeviceSafe(binderClass, "connect") { device, _ -> sendRealStatus(device, "connect") }
        hookBinderVoidDeviceSafe(binderClass, "getDeviceConfig") { device, _ -> sendRealStatus(device, "getDeviceConfig") }

        // changeAncMode
        hookBeforeSafe(binderClass, "changeAncMode", arrayOf(Int::class.java, BluetoothDevice::class.java)) {
            val mode = args[0] as? Int
            val device = args[1] as? BluetoothDevice ?: return@hookBeforeSafe
            if (!isOriGPod(device)) return@hookBeforeSafe
            lastOriGDevice = device; result = null
            mode?.let { sendOriGAnc(it) }
            sendRealStatus(device, "changeAncMode:$mode")
        }

        // callback register/unregister
        runCatching {
            val callbackClass = findClass("com.android.bluetooth.ble.app.IMiuiHeadsetCallback")
            hookBeforeSafe(binderClass, "register", arrayOf(callbackClass)) {
                val callback = args[0] ?: return@hookBeforeSafe
                if (lastOriGDevice != null) {
                    rememberCallback(callback)
                    result = null
                    Log.d(TAG, "register swallowed callback=$callback device=${lastOriGDevice?.address}")
                    requestBluetoothStatus("register")
                    sendRealStatus(lastOriGDevice, "register")
                    handler.postDelayed({ sendRealStatus(lastOriGDevice, "register-refresh") }, 350L)
                }
            }
            hookBeforeSafe(binderClass, "unregister", arrayOf(callbackClass, BluetoothDevice::class.java)) {
                val callback = args[0] ?: return@hookBeforeSafe
                val device = args[1] as? BluetoothDevice ?: return@hookBeforeSafe
                if (!isOriGPod(device)) return@hookBeforeSafe
                forgetCallback(callback)
                result = null
                Log.d(TAG, "unregister swallowed callback=$callback")
            }
        }.onFailure { Log.d(TAG, "callback hooks skipped (IMiuiHeadsetCallback not found)") }
    }

    /**
     * 安全的 hook 工厂：任何异常都被捕获，不影响原方法执行
     */
    private fun hookBeforeSafe(binderClass: Class<*>, methodName: String, paramTypes: Array<out Class<*>>, block: (HookParam.() -> Unit)) {
        runCatching {
            val method = binderClass.getMethod(methodName, *paramTypes).apply { isAccessible = true }
            hookBefore(method, block)
        }.onFailure { Log.w(TAG, "hookBeforeSafe $methodName failed", it) }
    }

    private fun hookBinderVoidDeviceSafe(binderClass: Class<*>, methodName: String, after: (BluetoothDevice?, String) -> Unit) {
        runCatching {
            hookBefore(binderClass.method(methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOriGPod(device)) return@hookBefore
                lastOriGDevice = device; result = null
                after(device, methodName)
                handler.postDelayed({ sendRealStatus(device, "$methodName-refresh") }, 350L)
            }
        }.onFailure { Log.w(TAG, "hookBinderVoidDeviceSafe $methodName failed", it) }
    }

    private fun hookAddressStringResultSafe(binderClass: Class<*>, methodName: String, label: String, forced: () -> String) {
        runCatching {
            hookBefore(binderClass.method(methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookBefore
                if (address == null || !isOriGAddress(address)) return@hookBefore
                result = forced()
                Log.d(TAG, "$label forced address=$address result=$result")
            }
        }.onFailure { Log.w(TAG, "hookAddressStringResultSafe $methodName failed", it) }
    }

    private fun hookAddressBooleanResultSafe(binderClass: Class<*>, methodNames: List<String>, label: String, forced: Boolean) {
        val methodName = methodNames.firstOrNull { name ->
            runCatching { binderClass.getMethod(name, String::class.java) }.isSuccess
        } ?: return
        runCatching {
            hookBefore(binderClass.method(methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookBefore
                if (address == null || !isOriGAddress(address)) return@hookBefore
                result = forced
                Log.d(TAG, "$label forced address=$address result=$forced")
            }
        }.onFailure { Log.w(TAG, "hookAddressBooleanResultSafe $methodName failed", it) }
    }

    private fun rememberCallback(callback: Any) {
        runCatching {
            (callMethod(callback, "asBinder") as? IBinder)?.let { callbacks[it] = callback }
        }.onFailure { Log.w(TAG, "rememberCallback failed", it) }
    }

    private fun forgetCallback(callback: Any) {
        runCatching {
            (callMethod(callback, "asBinder") as? IBinder)?.let { callbacks.remove(it) }
        }.onFailure { Log.w(TAG, "forgetCallback failed", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        val filter = IntentFilter().apply {
            listOf(
                MagicOriGAction.ACTION_PODS_CONNECTED,
                MagicOriGAction.ACTION_PODS_DISCONNECTED,
                MagicOriGAction.ACTION_PODS_BATTERY_CHANGED,
                MagicOriGAction.ACTION_PODS_ANC_CHANGED,
                MagicOriGAction.ACTION_CONFIG_CHANGED,
            ).forEach { addAction(it) }
        }
        runCatching {
            context?.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    safeOnStateBroadcast(intent)
                }
            }, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
            Log.d(TAG, "registered status receiver")
        }.onFailure { e ->
            Log.w(TAG, "registerReceiver failed: ${e.message}")
        }
        // 触发一次刷新请求
        runCatching {
            context?.sendBroadcast(Intent(MagicOriGAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth"); addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { Log.w(TAG, "initial refresh broadcast failed", it) }
    }

    private fun safeOnStateBroadcast(intent: Intent?) {
        if (intent == null) return
        try {
            when (intent.action) {
                MagicOriGAction.ACTION_CONFIG_CHANGED -> {
                    refreshConfig()
                    notifyRealStatus("config-changed")
                }
                MagicOriGAction.ACTION_PODS_CONNECTED -> {
                    currentAddress = intent.getStringExtra("address") ?: currentAddress
                    currentName = intent.getStringExtra("device_name") ?: currentName
                    currentAddress?.let { knownAddresses.add(it.uppercase()) }
                }
                MagicOriGAction.ACTION_PODS_DISCONNECTED -> {
                    currentAddress = intent.getStringExtra("address") ?: currentAddress
                }
                MagicOriGAction.ACTION_PODS_BATTERY_CHANGED -> {
                    currentAddress = intent.getStringExtra("address") ?: currentAddress
                    currentBattery = intent.batteryStatusFromExtras() ?: currentBattery
                    currentAddress?.let { knownAddresses.add(it.uppercase()) }
                }
                MagicOriGAction.ACTION_PODS_ANC_CHANGED -> {
                    currentAddress = intent.getStringExtra("address") ?: currentAddress
                    currentAnc = intent.getIntExtra("status", currentAnc)
                    currentAddress?.let { knownAddresses.add(it.uppercase()) }
                }
            }
            Log.d(TAG, "state action=${intent?.action} addr=$currentAddress anc=$currentAnc battery=${currentBattery?.debugString()}")
            notifyRealStatus("broadcast:${intent?.action}")
        } catch (e: Throwable) {
            Log.e(TAG, "safeOnStateBroadcast threw", e)
        }
    }

    private fun notifyRealStatus(reason: String) {
        val device = lastOriGDevice ?: return
        sendRealStatus(device, reason)
    }

    private fun sendRealStatus(device: BluetoothDevice?, reason: String) {
        if (device == null) return
        val address = device.address
        if (callbacks.isEmpty()) {
            Log.d(TAG, "sendRealStatus skipped: no callback registered (reason=$reason)")
            return
        }
        if (!isUpdatingState.compareAndSet(false, true)) {
            Log.d(TAG, "sendRealStatus skipped: already updating")
            return
        }
        val payload = realRefreshPayload()
        try {
            handler.post {
                callbacks.values.toList().forEach { callback ->
                    runCatching {
                        callMethod(callback, "refreshStatus", address, payload)
                        Log.d(TAG, "sent refreshStatus reason=$reason address=$address")
                    }.onFailure { forgetCallback(callback) }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handler.post failed", e)
        } finally {
            isUpdatingState.set(false)
        }
    }

    private fun realRefreshPayload(): String {
        val snapshot = runCatching { RfcommController.currentStatusSnapshot() }.getOrNull()
        val battery = snapshot?.battery ?: currentBattery
        val anc = currentAnc
        snapshot?.address?.let {
            currentAddress = it; knownAddresses.add(it.uppercase())
        }
        snapshot?.deviceName?.let { currentName = it }
        return RfcommController.miuiRefreshPayload(battery, anc)
    }

    private fun effectiveBattery(): BatteryParams? =
        runCatching { RfcommController.currentStatusSnapshot().battery }.getOrNull() ?: currentBattery

    private fun displayBattery(params: PodParams?): Int? =
        if (params?.isConnected == true) params.battery.coerceIn(0, 100) else null

    private fun displayWearState(battery: BatteryParams, fallback: Int): Int {
        val l = battery.left?.isConnected == true
        val r = battery.right?.isConnected == true
        return when { l && r -> 1; l -> 3; r -> 2; fallback != 0 -> fallback; else -> 1 }
    }

    private fun currentMiuiNotification(): Any? = runCatching {
        findClass("com.android.bluetooth.ble.app.headset.BluetoothHeadsetService")
            .getField("mMiuiBluetoothNotification").apply { isAccessible = true }.get(null)
    }.getOrNull()

    private fun requestBluetoothStatus(reason: String) {
        runCatching {
            if (packageName == "com.android.bluetooth") RfcommController.queryStatus()
            else context?.sendBroadcast(Intent(MagicOriGAction.ACTION_REFRESH_STATUS).apply {
                setPackage("com.android.bluetooth"); addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { Log.w(TAG, "requestBluetoothStatus failed: $reason", it) }
    }

    private fun oriGAncFromMiuiMode(mode: Int) = when (mode) { 1 -> 3; 2 -> 2; else -> 1 }

    private fun sendOriGAnc(miuiMode: Int) {
        currentAnc = oriGAncFromMiuiMode(miuiMode)
        val ctx = context ?: return
        runCatching {
            ctx.sendBroadcast(Intent(MagicOriGAction.ACTION_ANC_SELECT).apply {
                putExtra("status", currentAnc)
                setPackage("com.android.bluetooth")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            ctx.sendBroadcast(Intent(MagicOriGAction.ACTION_PODS_ANC_CHANGED).apply {
                putExtra("status", currentAnc)
                setPackage(ctx.packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure { Log.e(TAG, "sendOriGAnc broadcast failed", it) }
        Log.d(TAG, "sendOriGAnc miuiMode=$miuiMode → origMode=$currentAnc")
    }

    private fun isOriGPod(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        val result = name.contains("YUANDAO", ignoreCase = true) ||
                     name.contains("OriG", ignoreCase = true) ||
                     name.contains("NiceHCK", ignoreCase = true) ||
                     (address != null && isOriGAddress(address))
        if (result && address != null) knownAddresses.add(address.uppercase())
        return result
    }

    private fun isOriGAddress(address: String) = address.uppercase() in knownAddresses

    private fun BatteryParams?.debugString(): String =
        this?.let { "L${it.left?.battery}/${it.right?.battery}/${it.case?.battery}" } ?: "null"

    @Suppress("DEPRECATION")
    private fun Intent.batteryStatusFromExtras(): BatteryParams? {
        if (!hasExtra("left_connected") && !hasExtra("right_connected") && !hasExtra("case_connected")) return null
        return BatteryParams(
            left = PodParams(getIntExtra("left_battery", 0), getBooleanExtra("left_charging", false), getBooleanExtra("left_connected", false), 0),
            right = PodParams(getIntExtra("right_battery", 0), getBooleanExtra("right_charging", false), getBooleanExtra("right_connected", false), 0),
            case = PodParams(getIntExtra("case_battery", 0), getBooleanExtra("case_charging", false), getBooleanExtra("case_connected", false), 0)
        )
    }

    private fun Class<*>.method(name: String, vararg pt: Class<*>) =
        getDeclaredMethod(name, *pt).apply { isAccessible = true }

    private fun findClassOrNull(name: String) = runCatching { findClass(name) }.getOrNull()
}
