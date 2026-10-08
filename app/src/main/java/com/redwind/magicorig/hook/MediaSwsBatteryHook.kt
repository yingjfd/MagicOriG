package com.redwind.magicorig.hook

/**
 * MediaSwsBatteryHook — 作用域 com.hihonor.imedia.sws（荣耀音频切换/媒体输出服务）。
 *
 * 需求：在「当前音频输入输出设备」通知后面追加耳机左右耳电量。
 * 电量由 bluetooth 进程写入 Settings.Global["magicorig_battery"]（"L90,R85" 格式），
 * 本 hook 直接拦截 Notification.Builder.setContentText，含音频/蓝牙关键词时追加电量。
 *
 * （历史：最初误判为 com.spark.noticeflow 实况通知，已纠正。）
 */
object MediaSwsBatteryHook : HookContext() {
    private const val TAG = "MagicOriG-ImediaSws"

    override fun onHook() {
        runCatching {
            val m = Class.forName("android.app.Notification\$Builder")
                .getDeclaredMethod("setContentText", CharSequence::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val text = (chain.args.getOrNull(0) as? CharSequence)?.toString() ?: ""
                if (text.contains("18:5C") || text.contains("音频") ||
                    text.contains("蓝牙") || text.contains("耳机") || text.contains("输出设备")
                ) {
                    val suffix = readBatterySuffix()
                    if (suffix.isNotEmpty() && !text.contains(suffix)) {
                        Log.i(TAG, "通知追加电量: '$text' → '$text$suffix'")
                        chain.args[0] = text + suffix
                    }
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: Notification.setContentText 电量追加 (imedia.sws)")
        }.onFailure {
            Log.w(TAG, "install failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun readBatterySuffix(): String {
        return try {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return ""
            val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicorig_battery")
                ?: return ""
            if (!raw.contains(",")) return ""
            val left = raw.substringBefore(',').removePrefix("L").toIntOrNull() ?: -1
            val right = raw.substringAfter(',').removePrefix("R").toIntOrNull() ?: -1
            val l = if (left >= 0) "L${left}%" else ""
            val r = if (right >= 0) "R${right}%" else ""
            val s = "$l $r".trim()
            if (s.isEmpty()) "" else " · $s"
        } catch (_: Throwable) { "" }
    }
}
