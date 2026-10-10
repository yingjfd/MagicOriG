package com.redwind.magicorig.hook

/**
 * MediaSwsBatteryHook — 作用域 com.hihonor.imedia.sws（荣耀音频切换/媒体输出服务）。
 *
 * 【历史与现状】
 * 需求是把双耳电量放到音频切换 app 的连接通知里（格式「原道 OriG in　左耳 x% 右耳 x%」）。
 *
 * 曾在此进程内 hook 三层改写文案，全部无效 —— 实测该通知走荣耀自有渲染类
 * `android.media.QuaentCrearsaisSusts`（R8 混淆 + 动态 classloader，本进程 hook 不到），
 * 上游任何 set 层改写都会被它覆盖：
 *   1. Notification.Builder.setContentTitle/ContentText —— 通知是 custom RemoteViews，打不到；
 *   2. RemoteViews.setTextViewText —— 即使改成功（proceed(newArgs)）也随后被覆盖；
 *   3. RemoteViews.apply/reapply —— 渲染不发生在本进程。
 * 且这些 hook 的"改写成功"日志具有误导性（UI 从未变化）。
 *
 * ★ 最终生效方案在 **SystemUI 渲染层**（通知显示的最后一刻）：
 *   SystemUIHeadsetIconHook 中 hook RemoteViews.apply/reapply，View 树直接改写 ——
 *   已实拍通知栏显示「原道 OriG in / 左耳 100% 右耳 100%」。
 *
 * 蓝牙进程自己的电量通知（HuaweiStrongToastUtil.showBtNotification）已停用，
 * 断连取消保留（清理历史残留）。
 *
 * 本 hook 目前只作为 imedia.sws 作用域的占位（后续若需在该进程做其他事情在此扩展）。
 */
object MediaSwsBatteryHook : HookContext() {
    private const val TAG = "MagicOriG-ImediaSws"

    override fun onHook() {
        Log.i(
            TAG,
            "installed（说明：通知文案改写已移交 SystemUI 渲染层，本进程三层改写为死代码已清退）"
        )
    }

    /** "L90,R85" → "左耳 90% 右耳 85%"（保留备用；SystemUI 层有同款实现） */
    @Suppress("unused")
    private fun readBatteryPair(): String {
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return ""
            val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicorig_battery")
                ?: return ""
            if (!raw.contains(",")) return ""
            val left = raw.substringBefore(',').removePrefix("L").toIntOrNull() ?: -1
            val right = raw.substringAfter(',').removePrefix("R").toIntOrNull() ?: -1
            val l = if (left >= 0) "左耳 $left%" else ""
            val r = if (right >= 0) "右耳 $right%" else ""
            "$l $r".trim()
        } catch (_: Throwable) { "" }
    }
}
