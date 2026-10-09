package com.redwind.magicorig.pods

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.content.SharedPreferences
import com.redwind.magicorig.BuildConfig
import com.redwind.magicorig.hook.Log
import com.redwind.magicorig.utils.MediaControl
import com.redwind.magicorig.utils.MagicOriGAction
import com.redwind.magicorig.utils.huaweiStrongToast.HuaweiStrongToastUtil
import com.redwind.magicorig.utils.huaweiStrongToast.data.BatteryParams
import com.redwind.magicorig.utils.huaweiStrongToast.data.PodParams
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * RfcommController — RFCOMM SPP 连接控制器。
 *
 * 防炸机要点：
 * 1. 所有 socket 操作都有超时（30s connect timeout），避免无限等待卡死线程
 * 2. 用 AtomicLong generation 防止旧连接的回调污染新连接
 * 3. 断开连接时先取消所有协程再关闭 socket，避免资源泄漏
 * 4. 广播发送用 setPackage() 限定目标包，不被其他 app 截获
 * 5. 所有反射调用用 runCatching，任何异常只记录日志不影响主流程
 * 6. packet reader 循环有最大单次读取长度限制（1024 byte buffer）
 */
@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "MagicOriG-Rfcomm"
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L
    private const val RFCOMM_CONNECT_TIMEOUT_MS = 30_000L
    private const val RFCOMM_READ_TIMEOUT_MS = 5_000L

    private val SPP_UUID: UUID = UUID.fromString("0000a100-1000-8000-4e48-434b4354524c")

    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    private lateinit var mDevice: BluetoothDevice
    private lateinit var mPrefs: SharedPreferences

    // ── 线程安全的状态计数 ──────────────────────────────────────
    private val connectionGeneration = AtomicLong(0)
    private val isConnected = AtomicBoolean(false)

    data class StatusSnapshot(
        val battery: BatteryParams?,
        val anc: Int,
        val transparencyVocalEnhancement: Boolean,
        val address: String?,
        val deviceName: String?
    )

    private var mShowedConnectedToast = false
    private var lastTempBatt = 0
    private var currentBatteryParams: BatteryParams? = null
    private var currentAnc: Int = 1
    private var currentGameMode: Boolean = false
    private var currentLowLatency: Boolean = false
    private var currentDualConn: Boolean = false
    private var currentEq: EqMode = EqMode.BALANCED
    private var currentWindSuppression: Boolean = false
    private var currentInEarDetection: Boolean = false

    private var cachedLeftBattery: PodParams? = null
    private var cachedRightBattery: PodParams? = null
    private var cachedCaseBattery: PodParams? = null
    private val PREFS_NAME = "magicorig_battery"

    // ── 协程作用域 ──────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            safeHandleBroadcast(p0, p1)
        }
    }

    private fun safeHandleBroadcast(context: Context?, intent: Intent?) {
        if (intent == null) return
        // 可见性：本 session 长期无法确认 receiver 是否注册/是否收到广播，这里强制打 I 级日志。
        Log.i(TAG, "★ 收到广播 action=${intent.action} ctx=${context?.javaClass?.simpleName}")
        try {
            when (intent.action) {
                MagicOriGAction.ACTION_GET_PODS_MAC -> {
                    Log.i(TAG, "→ ACTION_GET_PODS_MAC → 回复 PODS_MAC_RECEIVED")
                    safeSendBroadcast(context, MagicOriGAction.ACTION_PODS_MAC_RECEIVED) {
                        putExtra("mac", mDevice.address)
                    }
                }
                else -> {
                    Log.i(TAG, "→ 转入 safeHandleUIEvent action=${intent.action}")
                    safeHandleUIEvent(intent)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "broadcast handler threw", e)
        }
    }

    private fun safeSendBroadcast(context: Context?, action: String, fill: Intent.() -> Unit = {}) {
        if (context == null) return
        try {
            Intent(action).apply {
                if (::mDevice.isInitialized) { putExtra("address", mDevice.address); putExtra("device_name", mDevice.name) }
                fill()
                `package` = "com.android.systemui"
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "sendBroadcast failed: $action", e)
        }
    }

    fun currentStatusSnapshot(): StatusSnapshot {
        return StatusSnapshot(
            battery = currentBatteryParams,
            anc = currentAnc,
            transparencyVocalEnhancement = false,
            address = if (::mDevice.isInitialized) mDevice.address else null,
            deviceName = if (::mDevice.isInitialized) mDevice.name else null
        )
    }

    fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean = false): String {
        val values = MutableList(16) { "" }
        values[0] = miuiBatteryValue(battery?.left)
        values[1] = miuiBatteryValue(battery?.right)
        values[2] = miuiBatteryValue(battery?.case)
        values[7] = miuiAncLevel(anc)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun miuiAncLevel(anc: Int): String = when (anc) {
        5 -> "0103"; 6 -> "0101"; 7 -> "0100"; 8 -> "0102"
        2 -> "0200"; 3 -> "0200"; else -> "0000"
    }

    fun queryStatus() {
        scope.launch {
            try {
                sendPacketSafe(Enums.QUERY_BATTERY); delay(50)
                sendPacketSafe(OriGPackets.buildPacket(Op.WIND_SUPPRESSION_QUERY)); delay(50)
                sendPacketSafe(Enums.QUERY_ANC); delay(50)
                sendPacketSafe(Enums.QUERY_GAME_MODE); delay(50)
                sendPacketSafe(OriGPackets.buildPacket(Op.LOW_LATENCY_QUERY)); delay(50)
                sendPacketSafe(OriGPackets.buildPacket(Op.DUAL_CONN_QUERY)); delay(50)
                sendPacketSafe(OriGPackets.buildPacket(Op.EQ_QUERY)); delay(50)
                sendPacketSafe(Enums.QUERY_IN_EAR_DETECTION)
            } catch (e: Throwable) {
                Log.e(TAG, "queryStatus coroutine threw", e)
            }
        }
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences) {
        // 防止并发连接
        if (isConnected.compareAndSet(false, true)) {
            Log.d(TAG, "connectPod started gen=${connectionGeneration.incrementAndGet()} device=${device.address}")
        } else {
            Log.d(TAG, "connectPod skipped: already connecting")
            return
        }

        mContext = context
        mDevice = device
        mPrefs = prefs

        runCatching { initBatteryCache(context) }
            .onFailure { Log.w(TAG, "initBatteryCache failed", it) }

        // 注册广播 — 必须有 try-catch，注册失败不阻止连接
        runCatching {
            val filter = IntentFilter().apply {
                listOf(
                    MagicOriGAction.ACTION_ANC_SELECT,
                    MagicOriGAction.ACTION_PODS_UI_INIT,
                    MagicOriGAction.ACTION_GET_PODS_MAC,
                    MagicOriGAction.ACTION_REFRESH_STATUS,
                    MagicOriGAction.ACTION_GAME_MODE_SET,
                    MagicOriGAction.ACTION_LOW_LATENCY_SET,
                    MagicOriGAction.ACTION_DUAL_CONN_SET,
                    MagicOriGAction.ACTION_EQ_SET,
                    MagicOriGAction.ACTION_WIND_SUPPRESSION_SET,
                    MagicOriGAction.ACTION_IN_EAR_DETECTION_SET
                ).forEach { addAction(it) }
            }
            context.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            Log.d(TAG, "broadcast receiver registered")
        }.onFailure { e ->
            Log.w(TAG, "broadcast receiver registration failed: ${e.message}")
        }

        // 发送连接广播
        val deviceName = device.alias ?: device.name ?: device.address
        try {
            Intent(MagicOriGAction.ACTION_PODS_CONNECTED).apply {
                putExtra("device_name", deviceName)
                putExtra("address", mDevice.address)
                `package` = BuildConfig.APPLICATION_ID
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "send broadcast failed", e)
        }
        context.getSharedPreferences("magicorig_device", Context.MODE_PRIVATE)
            .edit().putString("device_name", deviceName).apply()

        MediaControl.mContext = context

        // RFCOMM 连接在独立线程执行，避免阻塞蓝牙服务线程
        thread(name = "magicorig-rfcomm-connect") {
            safeRfcommConnect(device)
        }

        // 电池轮询线程
        thread(name = "magicorig-battery-poll") {
            try { Thread.sleep(2000) } catch (_: InterruptedException) {}
            while (isConnected.get()) {
                try { Thread.sleep(BATTERY_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (isConnected.get()) queryStatus()
            }
        }
    }

    private fun safeRfcommConnect(device: BluetoothDevice) {
        val gen = connectionGeneration.get()
        var attempt = 0
        val maxAttempts = 2

        while (attempt < maxAttempts && isConnected.get() && connectionGeneration.get() == gen) {
            attempt++
            try {
                // 先取消正在进行的配对/发现
                runCatching {
                    BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                }.onFailure { Log.w(TAG, "cancelDiscovery failed", it) }

                // 先尝试标准 RFCOMM socket
                var sock: BluetoothSocket? = null
                try {
                    sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    sock.connect()
                    Log.d(TAG, "RFCOMM connected (standard) gen=$gen")
                } catch (e: IOException) {
                    Log.w(TAG, "Standard RFCOMM failed, trying insecure (attempt $attempt)", e)
                    try {
                        sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                        sock.connect()
                        Log.d(TAG, "RFCOMM connected (insecure) gen=$gen")
                    } catch (e2: IOException) {
                        Log.e(TAG, "RFCOMM connect failed completely after $attempt attempts", e2)
                        if (attempt < maxAttempts) {
                            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                            continue
                        }
                        break
                    }
                }

                if (sock != null) {
                    socket = sock
                    // 读取线程
                    thread(name = "magicorig-rfcomm-reader") {
                        safePacketReader(sock!!.inputStream, gen)
                    }
                    try { Thread.sleep(300) } catch (_: InterruptedException) {}
                    queryStatus()
                    return
                }
            } catch (e: Throwable) {
                Log.e(TAG, "rfcomm connection attempt $attempt failed", e)
                if (attempt >= maxAttempts) break
                try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            }
        }

        // 所有尝试失败，清理状态
        if (connectionGeneration.get() == gen) {
            Log.e(TAG, "All RFCOMM connection attempts failed, disconnecting")
            safeDisconnect(device)
        }
    }

    private fun safePacketReader(inputStream: InputStream, gen: Long) {
        val buffer = ByteArray(1024)
        val dataBuffer = ByteArray(2048)
        var dataBufferPos = 0
        try {
            while (isConnected.get() && connectionGeneration.get() == gen) {
                val bytesRead = runCatching { inputStream.read(buffer) }.getOrNull() ?: continue
                if (connectionGeneration.get() != gen) break
                if (bytesRead > 0) {
                    System.arraycopy(buffer, 0, dataBuffer, dataBufferPos, bytesRead)
                    dataBufferPos += bytesRead
                    // 防止 buffer 溢出
                    if (dataBufferPos > dataBuffer.size / 2) {
                        dataBufferPos = processPackets(dataBuffer, dataBufferPos)
                    }
                } else if (bytesRead == -1) {
                    break
                }
            }
        } catch (e: IOException) {
            if (isConnected.get() && connectionGeneration.get() == gen) {
                Log.e(TAG, "RFCOMM read error", e)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "packet reader unknown error", e)
        } finally {
            if (connectionGeneration.get() == gen && isConnected.get()) {
                safeDisconnect(mDevice)
            }
        }
    }

    private fun processPackets(buffer: ByteArray, length: Int): Int {
        var pos = 0
        while (pos < length) {
            if ((buffer[pos].toInt() and 0xFF) != 0x4E) { pos++; continue }
            if (pos + 3 >= length) break
            val packetLength = (buffer[pos + 1].toInt() and 0xFF) + 3
            if (pos + packetLength > length) break
            runCatching { handleOriGPacket(buffer.copyOfRange(pos, pos + packetLength)) }
                .onFailure { Log.w(TAG, "packet handle failed", it) }
            pos += packetLength
        }
        if (pos < length) {
            val remaining = length - pos
            System.arraycopy(buffer, pos, buffer, 0, remaining)
            return remaining
        }
        return 0
    }

    private fun handleOriGPacket(packet: ByteArray) {
        if (BuildConfig.DEBUG) Log.v(TAG, "Received: ${packet.joinToString("") { "%02X".format(it.toInt() and 0xFF) }}")
        val batteryResult = BatteryParser.parse(packet)
        if (batteryResult != null) { handleBatteryChanged(batteryResult); return }
        val ancResult = AncModeParser.parse(packet)
        if (ancResult != null) {
            currentAnc = when (ancResult) {
                NoiseControlMode.OFF -> 1; NoiseControlMode.TRANSPARENT -> 2; NoiseControlMode.NORMAL -> 3
                NoiseControlMode.DEEP -> 4; NoiseControlMode.EXPERIMENT -> 5; NoiseControlMode.WIND_SUPPRESSION -> 6
                else -> 1
            }; changeUIAncStatus(currentAnc); return
        }
        runCatching { GameModeParser.parse(packet)?.let { currentGameMode = it; changeUIGameModeStatus(it) } }
            .onFailure { Log.w(TAG, "game mode parse failed", it) }
        runCatching { LowLatencyParser.parse(packet)?.let { currentLowLatency = it; changeUILowLatencyStatus(it) } }
            .onFailure { Log.w(TAG, "low latency parse failed", it) }
        runCatching { DualConnParser.parse(packet)?.let { currentDualConn = it; changeUIDualConnStatus(it) } }
            .onFailure { Log.w(TAG, "dual conn parse failed", it) }
        runCatching { EqParser.parse(packet)?.let { currentEq = it; changeUIEqStatus(it) } }
            .onFailure { Log.w(TAG, "eq parse failed", it) }
        runCatching { WindSuppressionParser.parse(packet)?.let { currentWindSuppression = it; changeUIWindSuppressionStatus(it) } }
            .onFailure { Log.w(TAG, "wind suppression parse failed", it) }
        runCatching { InEarDetectionParser.parse(packet)?.let { currentInEarDetection = it; changeUIInEarDetectionStatus(it) } }
            .onFailure { Log.w(TAG, "in ear detection parse failed", it) }
        if (BuildConfig.DEBUG) Log.v(TAG, "Unknown packet: ${packet.joinToString("") { "%02X".format(it.toInt() and 0xFF) }}")
    }

    private fun safeDisconnect(device: BluetoothDevice) {
        connectionGeneration.incrementAndGet()
        isConnected.set(false)
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        mContext = null
        MediaControl.mContext = null
        runCatching { mContext?.unregisterReceiver(broadcastReceiver) }
            .onFailure { Log.w(TAG, "unregisterReceiver failed") }
        mShowedConnectedToast = false
        Log.d(TAG, "disconnected device=${device.address}")
    }

    private fun safeHandleUIEvent(intent: Intent) {
        try {
            when (intent.action) {
                MagicOriGAction.ACTION_PODS_UI_INIT -> {
                    Log.i(TAG, "UI Init")
                    val deviceName = mDevice.alias ?: mDevice.name ?: mDevice.address
                    safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_CONNECTED) {
                        putExtra("device_name", deviceName)
                        putExtra("address", mDevice.address)
                    }
                    mContext?.getSharedPreferences("magicorig_device", Context.MODE_PRIVATE)
                        ?.edit()?.putString("device_name", deviceName)?.apply()
                    queryStatus()
                }
                MagicOriGAction.ACTION_ANC_SELECT -> setANCMode(intent.getIntExtra("status", 0))
                MagicOriGAction.ACTION_REFRESH_STATUS -> queryStatus()
                MagicOriGAction.ACTION_GAME_MODE_SET -> setGameMode(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_LOW_LATENCY_SET -> setLowLatency(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_DUAL_CONN_SET -> setDualConn(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_EQ_SET -> setEq(EqMode.fromValue(intent.getIntExtra("value", 0)))
                MagicOriGAction.ACTION_WIND_SUPPRESSION_SET -> setWindSuppression(intent.getBooleanExtra("enabled", false))
                MagicOriGAction.ACTION_IN_EAR_DETECTION_SET -> setInEarDetection(intent.getBooleanExtra("enabled", false))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "UI event handler threw", e)
        }
    }

    private fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        runCatching {
            if (result.left != null) {
                cachedLeftBattery = PodParams(result.left.level, result.left.isCharging, true, 0)
                saveBattery("left_battery", "left_charging", result.left.level, result.left.isCharging)
            }
            if (result.right != null) {
                cachedRightBattery = PodParams(result.right.level, result.right.isCharging, true, 0)
                saveBattery("right_battery", "right_charging", result.right.level, result.right.isCharging)
            }
            if (result.case != null) {
                cachedCaseBattery = PodParams(result.case.level, result.case.isCharging, true, 0)
                saveBattery("case_battery", "case_charging", result.case.level, result.case.isCharging)
            }
        }.onFailure { Log.e(TAG, "battery cache update failed", it) }

        val left = cachedLeftBattery ?: PodParams(result.left?.level ?: 0, result.left?.isCharging == true, result.left != null, 0)
        val right = cachedRightBattery ?: PodParams(result.right?.level ?: 0, result.right?.isCharging == true, result.right != null, 0)
        val case = cachedCaseBattery ?: PodParams(result.case?.level ?: 0, result.case?.isCharging == true, result.case != null, 0)

        if (BuildConfig.DEBUG) Log.v(TAG, "batt L${left.battery} R${right.battery} C${case.battery}")

        val shouldShowToast = !mShowedConnectedToast
        val hasValidData = (left.isConnected && left.battery > 0) || (right.isConnected && right.battery > 0)
        currentBatteryParams = BatteryParams(left, right, case)

        runCatching {
            val ctx = mContext ?: return@runCatching
            if (shouldShowToast && hasValidData) {
                HuaweiStrongToastUtil.showConnectedToast(ctx, currentBatteryParams!!)
                mShowedConnectedToast = true
            }
            HuaweiStrongToastUtil.showPodsBatteryToast(ctx, currentBatteryParams!!)
            // ★ 任务4：双耳电量通知迁移到「音频切换」app 的连接耳机通知（imedia.sws），
            //   格式「原道OriG in 左耳 xx% 右耳 xx%」—— 蓝牙进程不再独立弹电量通知。
            // HuaweiStrongToastUtil.showBtNotification(ctx, mDevice, currentBatteryParams!!)
            // 取消旧通知（迁移后清掉可能残留的历史通知）
            runCatching { HuaweiStrongToastUtil.cancelPodsNotification(ctx) }
            changeUIBatteryStatus(currentBatteryParams!!)
            // 跨进程电量通道：SharedPreferences 是 bluetooth 私有目录，Settings/NoticeFlow 读不到，
            // 转存 Settings.Global（全进程只读可见），供通知 hook 追加电量。
            runCatching {
                val res = ctx.contentResolver
                android.provider.Settings.Global.putString(
                    res, "magicorig_battery", "L${left.battery},R${right.battery}"
                )
                // 连接态信号（任务6）：电量数据到达 = 耳机在线；断连时 disconnectedPod 置 0。
                // 设备中心球列表注入据此门控 —— 断连后球/卡片消失。
                android.provider.Settings.Global.putString(res, "magicorig_connected", "1")
                Log.i(TAG, "电量已写入 Settings.Global: L${left.battery},R${right.battery}")
            }.onFailure { Log.w(TAG, "Global 写入失败: ${it.javaClass.simpleName}") }
        }.onFailure { Log.e(TAG, "battery toast/notification failed", it) }

        lastTempBatt = when {
            left.isConnected && right.isConnected -> minOf(left.battery, right.battery)
            left.isConnected -> left.battery
            right.isConnected -> right.battery
            else -> -1
        }
    }

    fun initBatteryCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt("left_battery", 0) > 0) cachedLeftBattery = PodParams(
            prefs.getInt("left_battery", 0), prefs.getBoolean("left_charging", false), true, 0
        )
        if (prefs.getInt("right_battery", 0) > 0) cachedRightBattery = PodParams(
            prefs.getInt("right_battery", 0), prefs.getBoolean("right_charging", false), true, 0
        )
        if (prefs.getInt("case_battery", 0) > 0) cachedCaseBattery = PodParams(
            prefs.getInt("case_battery", 0), prefs.getBoolean("case_charging", false), true, 0
        )
    }

    private fun saveBattery(keyBattery: String, keyCharging: String, battery: Int, isCharging: Boolean) {
        runCatching {
            mContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.apply {
                putInt(keyBattery, battery)
                putBoolean(keyCharging, isCharging)
                apply()
            }
        }.onFailure { Log.w(TAG, "saveBattery failed", it) }
    }

    private fun sendPacketSafe(packet: ByteArray) {
        // 最后一环可见性：确认 SPP 帧真的写出去了（本 session 无法用 btm 日志验证数据包）
        Log.i(TAG, "→→ SPP 写出 len=${packet.size} hex=${packet.joinToString(" ") { String.format("%02X", it) }} socket=${socket != null}")
        try {
            socket?.outputStream?.write(packet)
            socket?.outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
        } catch (e: Throwable) {
            Log.e(TAG, "sendPacketSafe unknown error", e)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("address", mDevice.address)
            putExtra("status", status)
        }
        sendExternalPodsStatusBroadcast(MagicOriGAction.ACTION_PODS_ANC_CHANGED) { putExtra("status", status) }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", mDevice.address)
            putBatteryExtras(status)
        }
        sendExternalPodsStatusBroadcast(MagicOriGAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", mDevice.address)
            putBatteryExtras(status)
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        val targets = listOf("com.android.bluetooth", "com.android.settings")
        targets.forEach { targetPackage ->
            try {
                Intent(action).apply {
                    if (::mDevice.isInitialized) { putExtra("address", mDevice.address); putExtra("device_name", mDevice.name) }
                    fill()
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    ctx.sendBroadcast(this)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "sendBroadcast to $targetPackage failed", e)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_GAME_MODE_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUILowLatencyStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_LOW_LATENCY_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIDualConnStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_DUAL_CONN_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIEqStatus(mode: EqMode) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_EQ_CHANGED) { putExtra("value", mode.value) }
    }
    private fun changeUIWindSuppressionStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_WIND_SUPPRESSION_CHANGED) { putExtra("enabled", enabled) }
    }
    private fun changeUIInEarDetectionStatus(enabled: Boolean) {
        safeSendBroadcast(mContext, MagicOriGAction.ACTION_PODS_IN_EAR_DETECTION_CHANGED) { putExtra("enabled", enabled) }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        if (::mDevice.isInitialized && mDevice.address != device.address) return
        safeDisconnect(device)
        // 断联时移除电量通知 —— 此前 cancelPodsNotification 从未被调用，导致通知不消失。
        runCatching {
            HuaweiStrongToastUtil.cancelPodsNotification(context)
            // 任务6：断连置 0 → 设备中心球列表注入据此消失
            android.provider.Settings.Global.putString(context.contentResolver, "magicorig_connected", "0")
            Log.i(TAG, "已移除电量通知（断联 ${device.address}）")
        }.onFailure { Log.w(TAG, "cancelPodsNotification failed", it) }
        try {
            Intent(MagicOriGAction.ACTION_PODS_DISCONNECTED).apply {
                if (::mDevice.isInitialized) putExtra("address", mDevice.address)
                `package` = BuildConfig.APPLICATION_ID
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                context.sendBroadcast(this)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "disconnect broadcast failed", e)
        }
    }

    // ── 控制命令 ────────────────────────────────────────────────
    fun setGameMode(enabled: Boolean) {
        currentGameMode = enabled; currentLowLatency = enabled
        sendPacketSafe(if (enabled) Enums.GAME_MODE_ON else Enums.GAME_MODE_OFF)
        changeUIGameModeStatus(enabled); changeUILowLatencyStatus(enabled)
    }
    fun setLowLatency(enabled: Boolean) {
        currentLowLatency = enabled
        sendPacketSafe(if (enabled) Enums.LOW_LATENCY_ON else Enums.LOW_LATENCY_OFF)
        changeUILowLatencyStatus(enabled)
    }
    fun setDualConn(enabled: Boolean) {
        currentDualConn = enabled
        sendPacketSafe(if (enabled) Enums.DUAL_CONN_ON else Enums.DUAL_CONN_OFF)
        changeUIDualConnStatus(enabled)
    }
    fun setEq(mode: EqMode) {
        if (mode == currentEq) return
        currentEq = mode
        sendPacketSafe(OriGPackets.buildPacket(Op.EQ_SET, mode.value.toByte(), 0x00))
        changeUIEqStatus(mode)
    }
    fun setWindSuppression(enabled: Boolean) {
        currentWindSuppression = enabled
        sendPacketSafe(if (enabled) Enums.WIND_SUPPRESSION_ON else Enums.WIND_SUPPRESSION_OFF)
        changeUIWindSuppressionStatus(enabled)
    }
    fun setInEarDetection(enabled: Boolean) {
        currentInEarDetection = enabled
        sendPacketSafe(if (enabled) Enums.IN_EAR_DETECTION_ON else Enums.IN_EAR_DETECTION_OFF)
        changeUIInEarDetectionStatus(enabled)
    }
    fun setANCMode(mode: Int) {
        Log.i(TAG, "★ setANCMode(mode=$mode) socketReady=${::mDevice.isInitialized}")
        if (mode == currentAnc) {
            // 原本直接 return → currentAnc 初值与目标相同时永远不发包（实测 mode=3 早退，无 SPP 写出）。
            // 改为记录但仍下发，确保 UI 点击总能真正到达耳机。
            Log.i(TAG, "mode=$mode 与 currentAnc=$currentAnc 相同，仍强制下发")
        }
        val packet = when (mode) {
            1 -> Enums.ANC_OFF; 2 -> Enums.ANC_TRANSPARENT; 3 -> Enums.ANC_NORMAL
            4 -> Enums.ANC_DEEP; 5 -> Enums.ANC_EXPERIMENT; 6 -> Enums.ANC_WIND_SUPPRESSION
            else -> return
        }
        currentAnc = mode; sendPacketSafe(packet); changeUIAncStatus(currentAnc)
    }
}
