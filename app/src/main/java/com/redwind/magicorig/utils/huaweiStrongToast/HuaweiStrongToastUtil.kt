package com.redwind.magicorig.utils.huaweiStrongToast

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.Json
import com.redwind.magicorig.BuildConfig
import com.redwind.magicorig.utils.SystemApisUtils
import com.redwind.magicorig.utils.huaweiStrongToast.data.BatteryParams
import com.redwind.magicorig.utils.huaweiStrongToast.data.HuaweiStrongToastBean
import com.redwind.magicorig.utils.huaweiStrongToast.data.HuaweiStrongToastCategory

/**
 * HuaweiStrongToastUtil — MagicOS 强提示工具。
 *
 * MagicOS 使用与 HyperOS 类似的 "Strong Toast" 机制，
 * 但包名和类别不同：
 * - 包名：com.huawei.android.bluetooth
 * - 类别：使用 HUAWEI_STRONG_TOAST_ACTION
 */
@SuppressLint("WrongConstant")
object HuaweiStrongToastUtil {
    private const val TAG = "MagicOriG-Toast"
    var lastPodsTimestamp = -1L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun showPodsBatteryToast(context: Context, batteryParams: BatteryParams) {
        if (SystemApisUtils.isHyperOS) return  // HyperOS 设备走 MiuiStrongToast 路径
        val leftConnected = batteryParams.left?.isConnected == true
        val rightConnected = batteryParams.right?.isConnected == true
        val left = batteryParams.left?.battery ?: 0
        val leftCharging = batteryParams.left?.isCharging == true
        val right = batteryParams.right?.battery ?: 0
        val rightCharging = batteryParams.right?.isCharging == true

        val leftText = if (leftConnected) "$left%" else ""
        val rightText = if (rightConnected) "$right%" else ""

        val bean = HuaweiStrongToastBean(
            leftText = leftText,
            leftTextColor = if (leftCharging) Color.GREEN
                            else if (left <= 20) Color.RED
                            else Color.WHITE,
            rightText = rightText,
            rightTextColor = if (rightCharging) Color.GREEN
                             else if (right <= 20) Color.RED
                             else Color.WHITE,
            category = HuaweiStrongToastCategory.BATTERY_VIDEO_TEXT
        )

        val jsonStr = json.encodeToString(HuaweiStrongToastBean.serializer(), bean)
        val bundle = Bundle().apply {
            putString("param", jsonStr)
            putString("packageName", BuildConfig.APPLICATION_ID)
        }
        try {
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass.getMethod(
                "setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java
            ).invoke(service, 1, "huawei_strong_toast_action", bundle)
            lastPodsTimestamp = System.currentTimeMillis()
            Log.d(TAG, "showPodsBatteryToast OK left=$leftText right=$rightText")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Huawei Strong Toast", e)
        }
    }

    fun showConnectedToast(context: Context, batteryParams: BatteryParams) {
        if (SystemApisUtils.isHyperOS) return
        // 连接时的简洁 toast
        val bean = HuaweiStrongToastBean(
            leftText = "已连接",
            leftTextColor = Color.parseColor("#4CAF50"),
            rightText = "",
            rightTextColor = Color.WHITE,
            category = HuaweiStrongToastCategory.TEXT_ONLY
        )
        val jsonStr = json.encodeToString(HuaweiStrongToastBean.serializer(), bean)
        val bundle = Bundle().apply { putString("param", jsonStr) }
        try {
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass.getMethod(
                "setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java
            ).invoke(service, 1, "huawei_connected_toast_action", bundle)
            Log.d(TAG, "showConnectedToast OK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show connected toast", e)
        }
    }

    /**
     * 尝试通过标准 Android 通知渠道发送 TWS 连接通知（MagicOS 备用方案）。
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun showBtNotification(context: Context, device: BluetoothDevice?, batteryParams: BatteryParams) {
        if (device == null) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "magicorig_bluetooth"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(channelId, "MagicOriG 蓝牙", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        val left = batteryParams.left?.battery ?: 0
        val right = batteryParams.right?.battery ?: 0
        val content = "左耳 ${left}% · 右耳 ${right}%"
        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OriG in 已连接")
            .setContentText(content)
            .setAutoCancel(true)
            .build()
        nm.notify(hashCode() and 0xFFFF, notification)
    }

    fun cancelPodsNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(hashCode() and 0xFFFF)
    }
}
