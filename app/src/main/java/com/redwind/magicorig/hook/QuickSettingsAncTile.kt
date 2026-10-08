package com.redwind.magicorig.hook

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.provider.Settings

/**
 * QuickSettingsAncTile — 控制中心降噪快捷磁贴。
 *
 * 任务4需求：「将 Origin 添加到设备中心，在控制中心可以直接调节降噪模式」。
 * 荣耀私有设备中心需要深度的 MagicOS 逆向（EarphoneNoiseSectionController 已在控制中心 APK 中定位），
 * 但作为立即可用方案，这里实现标准 Android Quick Settings TileService，
 * 点击循环切换：实验性降噪 → 透传 → 关闭 → 实验性降噪
 *
 * 状态持久化：Settings.Global["magicorig_last_anc"] = "1" / "2" / "5"
 * 降噪下发：广播 com.redwind.magicorig.ACTION_ANC_SELECT
 */
class QuickSettingsAncTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val current = try {
            Settings.Global.getString(contentResolver, "magicorig_last_anc")
        } catch (_: Throwable) { "5" }
        // 循环切换：5(实验性降噪) → 2(透传) → 1(关闭) → 5
        val next = when (current) {
            "5" -> "2"
            "2" -> "1"
            else -> "5"
        }
        // 持久化
        try {
            Settings.Global.putString(contentResolver, "magicorig_last_anc", next)
        } catch (_: Throwable) {}
        // 下发降噪广播
        val status = next.toIntOrNull() ?: 5
        sendBroadcast(Intent("com.redwind.magicorig.ACTION_ANC_SELECT").putExtra("status", status))
        updateTile()
    }

    private fun updateTile() {
        val current = try {
            Settings.Global.getString(contentResolver, "magicorig_last_anc")
        } catch (_: Throwable) { "5" }
        val qsTile = qsTile ?: return
        when (current) {
            "5" -> {
                qsTile.label = "实验性降噪"
                qsTile.contentDescription = "降噪模式：实验性降噪"
                qsTile.state = Tile.STATE_ACTIVE
            }
            "2" -> {
                qsTile.label = "透传"
                qsTile.contentDescription = "降噪模式：透传"
                qsTile.state = Tile.STATE_ACTIVE
            }
            else -> {
                qsTile.label = "降噪关闭"
                qsTile.contentDescription = "降噪模式：关闭"
                qsTile.state = Tile.STATE_INACTIVE
            }
        }
        qsTile.updateTile()
    }
}
