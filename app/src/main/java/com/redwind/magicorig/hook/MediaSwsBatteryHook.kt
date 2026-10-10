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
                    // chain.args 是只读 List（Collections$UnmodifiableList，直接 set 会抛异常），
                    // 官方姿势：拷贝成数组后 proceed(newArgs)
                    val newArgs = chain.args.toTypedArray()
                    newArgs[0] = OUR_TITLE
                    return@intercept chain.proceed(newArgs)
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
                        val newArgs = chain.args.toTypedArray()
                        newArgs[0] = bat
                        return@intercept chain.proceed(newArgs)
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
                    if (now - lastRvLogAt > 1000) {   // 全量观察（1s 限频）
                        lastRvLogAt = now
                        Log.i(TAG, "RV set(id=${chain.args.getOrNull(0)}): '$t'")
                    }
                    // 对位（按通知栏实测 UI 结构）：
                    //   标题行（大字）原文「当前音频输入输出设备」→ 「原道 OriG in」
                    //   内容行（小字）原文「原道OriG in」→ 「左耳 x% 右耳 x%」
                    //   合并呈现即「原道 OriG in　左耳 x% 右耳 x%」
                    val out = when {
                        t.contains("左耳") && t.contains("%") -> null   // 已是电量，幂等
                        t == "当前音频输入输出设备" || (t.contains("音频") && t.contains("设备") && t.length <= 15) -> OUR_TITLE
                        t.contains("OriG") || t.contains("原道") || t.contains("18:5C") -> bat
                        else -> null
                    }
                    if (out != null && out != t) {
                        Log.i(TAG, "★ RV 改写(id=${chain.args.getOrNull(0)}): '$t' → '$out'")
                        // 只读 List → 数组拷贝 + proceed(newArgs)
                        val newArgs = chain.args.toTypedArray()
                        newArgs[1] = out
                        return@intercept chain.proceed(newArgs)
                    }
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: RemoteViews.setTextViewText 文案改写")
        }.onFailure {
            Log.w(TAG, "install RemoteViews failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ④ ★ 终极层：RemoteViews.apply/reapply 返回的是即将上屏的 View 树 ——
        //    在这里改 TextView 文本，渲染前最后一刻，不可能被上层覆盖。
        //    （set 层改写实测有生效日志但 UI 仍显示原文 → 最终 post 的实例绕过了 set 拦截）
        runCatching {
            val rvCls = Class.forName("android.widget.RemoteViews")
            var hookedApply = 0
            for (mn in arrayOf("apply", "reapply")) {
                val m = rvCls.declaredMethods.firstOrNull {
                    it.name == mn && it.parameterCount >= 1 &&
                            android.view.View::class.java.isAssignableFrom(it.returnType)
                } ?: continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed()
                    val bat = readBatteryPair()
                    if (bat.isNotEmpty() && ret is android.view.View) {
                        var changed = 0
                        fun walk(v: android.view.View) {
                            if (v is android.widget.TextView) {
                                val cur = v.text?.toString() ?: ""
                                when (cur) {
                                    "当前音频输入输出设备" -> { v.text = OUR_TITLE; changed++ }
                                    "原道OriG in" -> { v.text = bat; changed++ }
                                }
                            } else if (v is android.view.ViewGroup) {
                                for (i in 0 until v.childCount) walk(v.getChildAt(i))
                            }
                        }
                        walk(ret)
                        if (changed > 0) Log.i(TAG, "★★ apply 层改写 $changed 处（$mn）")
                    }
                    ret
                }
                hookedApply++
            }
            Log.i(TAG, "installed: RemoteViews.apply/reapply [View 层改写] ($hookedApply)")
        }.onFailure {
            Log.w(TAG, "install apply hook failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ⑤ ★ 荣耀自有渲染类：通知真正的构建/渲染走混淆类
        //    android.media.QuaentCrearsaisSusts（栈实证：getNotificationBuilder
        //    → QuaentCrearsaisSusts.setTextViewText），set 层怎么改都会被它的
        //    后续逻辑覆盖，UI 永远显示原文。这里 hook 它的 apply 类方法
        //    （返回 View 的方法），在 View 层最终改写。
        //    ★ 该类在 onHook 时刻尚未加载（实测 ClassNotFoundException）→ 延迟轮询 hook。
        Thread {
            var cls: Class<*>? = null
            var tries = 0
            while (cls == null && tries < 60) {
                cls = runCatching {
                    Class.forName("android.media.QuaentCrearsaisSusts", false, appClassLoader)
                }.getOrNull()
                if (cls == null) { tries++; Thread.sleep(500) }
            }
            if (cls == null) {
                Log.w(TAG, "混淆类 30s 内未出现，放弃 ⑤ hook")
                return@Thread
            }
            runCatching {
                val ms = cls.declaredMethods.filter {
                    it.parameterCount >= 1 &&
                            android.view.View::class.java.isAssignableFrom(it.returnType)
                }
                for (m in ms) {
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val ret = chain.proceed()
                        val n = rewriteNoticeView(ret)
                        if (n > 0) Log.i(TAG, "★★★ 混淆类 apply 层改写 $n 处（${m.name}）")
                        ret
                    }
                }
                Log.i(TAG, "installed: QuaentCrearsaisSusts apply 类方法 [混淆渲染层] (${ms.size}, 延迟 ${tries * 500}ms)")
            }.onFailure {
                Log.w(TAG, "install 混淆类 apply failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }.start()
    }

    /**
     * View 层改写：遍历 View 树，把两行原文换成目标文案。
     * 标题行「当前音频输入输出设备」→「原道 OriG in」
     * 内容行「原道OriG in」→「左耳 x% 右耳 x%」
     */
    private fun rewriteNoticeView(root: Any?): Int {
        val bat = readBatteryPair()
        if (bat.isEmpty() || root !is android.view.View) return 0
        var changed = 0
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView) {
                val cur = v.text?.toString() ?: ""
                when (cur) {
                    "当前音频输入输出设备" -> { v.text = OUR_TITLE; changed++ }
                    "原道OriG in" -> { v.text = bat; changed++ }
                }
            } else if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return changed
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
