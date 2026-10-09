package com.redwind.magicorig.hook

/**
 * MediaSwsBatteryHook — 作用域 com.hihonor.imedia.sws（荣耀音频切换/媒体输出服务）。
 *
 * 任务4：双耳电量通知从蓝牙进程迁移到本 app 的「连接耳机默认通知」——
 *   标题 → 「原道 OriG in」，内容 → 「左耳 xx% 右耳 xx%」
 *   （整体呈现为「原道 OriG in  左耳 ...% 右耳 ...%」）
 * 电量来自 bluetooth 进程写入的 Settings.Global["magicorig_battery"]（"L90,R85"）。
 *
 * 识别条件：通知含耳机/连接/音频设备关键词（见 MATCH_KEYWORDS），
 * 且电量可读；已是目标格式则幂等跳过。
 */
object MediaSwsBatteryHook : HookContext() {
    private const val TAG = "MagicOriG-ImediaSws"
    private const val OUR_TITLE = "原道 OriG in"
    @Volatile private var lastRvLogAt = 0L
    private val MATCH_KEYWORDS = arrayOf(
        "已连接", "连接", "耳机", "音频", "输出设备", "蓝牙", "18:5C", "OriG", "原道"
    )

    override fun onHook() {
        // ① 标题 → 原道 OriG in
        runCatching {
            val m = Class.forName("android.app.Notification\$Builder")
                .getDeclaredMethod("setContentTitle", CharSequence::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val text = (chain.args.getOrNull(0) as? CharSequence)?.toString() ?: ""
                val bat = readBatteryPair()
                val matched = bat.isNotEmpty() && MATCH_KEYWORDS.any { text.contains(it) }
                if (matched && text != OUR_TITLE) {
                    Log.i(TAG, "通知标题改写: '$text' → '$OUR_TITLE'")
                    chain.args[0] = OUR_TITLE
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: Notification.setContentTitle 标题改写")
        }.onFailure {
            Log.w(TAG, "install setContentTitle failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ② 内容 → 左耳 xx% 右耳 xx%
        runCatching {
            val m = Class.forName("android.app.Notification\$Builder")
                .getDeclaredMethod("setContentText", CharSequence::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val text = (chain.args.getOrNull(0) as? CharSequence)?.toString() ?: ""
                val bat = readBatteryPair()   // "左耳 88% 右耳 90%"
                val matched = bat.isNotEmpty() && MATCH_KEYWORDS.any { text.contains(it) }
                if (matched) {
                    val idempotent = text == bat ||
                        (text.contains("左耳") && text.contains("右耳") && text.contains("%"))
                    if (!idempotent) {
                        Log.i(TAG, "通知内容改写: '$text' → '$bat'")
                        chain.args[0] = bat
                    }
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: Notification.setContentText 内容改写（电量）")
        }.onFailure {
            Log.w(TAG, "install setContentText failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ③ 实测：该通知走 custom RemoteViews（DecoratedCustomViewStyle，无 title/text extras），
        //    文案真正入口是 RemoteViews.setTextViewText —— 在此匹配关键词改写。
        runCatching {
            val m = Class.forName("android.widget.RemoteViews")
                .getDeclaredMethod("setTextViewText", Int::class.javaPrimitiveType, CharSequence::class.java)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val t = (chain.args.getOrNull(1) as? CharSequence)?.toString() ?: ""
                val bat = readBatteryPair()
                if (bat.isNotEmpty() && t.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    if (now - lastRvLogAt > 3000) {   // 观察限频
                        lastRvLogAt = now
                        Log.i(TAG, "RemoteViews 文本候选(id=${chain.args.getOrNull(0)}): '$t'")
                    }
                    val out = when {
                        t.contains("OriG") || t.contains("原道") || t.contains("18:5C") -> OUR_TITLE
                        MATCH_KEYWORDS.any { t.contains(it) } &&
                            (t.contains("连接") || t.contains("音频") || t.contains("输出设备") || t.contains("蓝牙")) -> bat
                        else -> null
                    }
                    if (out != null && out != t) {
                        Log.i(TAG, "★ RemoteViews 改写: '$t' → '$out'")
                        chain.args[1] = out
                    }
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: RemoteViews.setTextViewText 文案改写")
        }.onFailure {
            Log.w(TAG, "install RemoteViews failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** "L90,R85" → "左耳 90% 右耳 85%"（任一耳有效即返回，缺的耳省略） */
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
