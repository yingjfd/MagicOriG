package com.redwind.magicorig.hook

import android.content.Intent
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import com.redwind.magicorig.config.ConfigManager

/**
 * SettingsHeadsetHook — 阻止系统设置中将 OriG 识别为小米耳机。
 * 从 HyperOriG 完整移植。
 */
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "MagicOriG-Settings"

    override fun onHook() {
        // ★ bug修复（蓝牙界面显示耳机本体真实模式）：注册状态广播接收器 ——
        //   进页面时发的只读查询（ACTION_REFRESH_STATUS）回包后，bluetooth 进程会
        //   广播 ACTION_PODS_ANC_CHANGED(status)；这里实时把它映射为面板选中项。
        //   全程只读查询+UI 高亮，不产生"调模式"数据包。
        //   onHook 时刻 currentApplication 可能尚未就绪（曾静默跳过）→ 带重试。
        runCatching { registerAncReceiver(retryLeft = 4) }
            .onFailure { Log.w(TAG, "注册 ANC 状态接收器异常: ${it.javaClass.simpleName}: ${it.message}") }

        // HeadsetIDConstants 是 MIUI/HyperOS 专有类。真机 dexdump 确认 MagicOS 11 的
        // com.android.settings 里根本没有它（实际只有 TypecEarPhoneController /
        // SmartEarphoneImp / DeviceEarphoneCfgObserver），所以原实现永远走
        // runCatching 静默跳过，看起来 onHook OK，实际一行代码都没挂钩。
        // 这里改成先探测类是否存在，再决定是否挂钩，并把结果打到 LSPosed 日志。
        val targetClass = "com.android.settings.bluetooth.HeadsetIDConstants"
        // 注意：这里不能用 .onFailure { ...; return } —— 那是非局部 return，
        // 会直接退出整个 onHook()，导致后面所有 hook 都装不上（真机踩过）。
        val legacyPresent = runCatching { findClass(targetClass) }.isSuccess
        if (legacyPresent) {
            runCatching {
                val method = findMethod(targetClass, "checkSupport", String::class.java)
                module.hook(method).intercept { chain ->
                    val original = chain.proceed()
                    val support = chain.args.getOrNull(0) as? String
                    val fakeId = fakeDeviceId()
                    if (support != null && (support.startsWith(fakeId) || support.contains(fakeId))) {
                        Log.i(TAG, "checkSupport blocked fakeId=$fakeId support=$support")
                        false
                    } else original
                }
                Log.i(TAG, "checkSupport hook installed on $targetClass")
            }.onFailure { Log.w(TAG, "checkSupport hook failed: ${it.javaClass.simpleName}") }
        } else {
            Log.w(TAG, "target class absent on this ROM: $targetClass — hook not installed")
        }

        // ── 荣耀降噪 UI：强制可用 ────────────────────────────────────
        // MagicOS Settings 里降噪 Controller 全套存在，只是没对我们设备启用：
        //   hwcontrollers/ancsettings/AncSwitchController
        //   hwcontrollers/profilesettings/NoiseControlController
        //   hwcontrollers/profilesettings/NoiseControlPanelController
        // 每个都带标准 Settings 门面 getAvailabilityStatus()（返回 int，AVAILABLE=0）。
        val ancTargets = listOf(
            "com.android.settings.bluetooth.hwcontrollers.ancsettings.AncSwitchController",
            "com.android.settings.bluetooth.hwcontrollers.profilesettings.NoiseControlController",
            "com.android.settings.bluetooth.hwcontrollers.profilesettings.NoiseControlPanelController"
        )
        for (cls in ancTargets) {
            runCatching {
                val clazz = findClass(cls)
                val methods = clazz.declaredMethods.filter {
                    it.name == "getAvailabilityStatus" && it.parameterCount == 0
                }
                if (methods.isEmpty()) throw NoSuchMethodException("no getAvailabilityStatus")
                for (m in methods) {
                    if (m.returnType != Int::class.javaPrimitiveType) {
                        Log.w(TAG, "skip ${cls.substringAfterLast('.')}.getAvailabilityStatus 返回 ${m.returnType}")
                        continue
                    }
                    m.isAccessible = true
                    module.hook(m).intercept {
                        Log.i(TAG, "强制可用: ${cls.substringAfterLast('.')}")
                        0 // AvailabilityStatus.AVAILABLE
                    }
                }
                Log.i(TAG, "installed: ${cls.substringAfterLast('.')} 强制可用")

                // 反汇编确认 DeviceProfilesSettings.onCreate 确实 new-instance 了
                // NoiseControlController / NoiseControlPanelController，并在 b3/j3 里调
                // displayPreference()。但 getAvailabilityStatus 从未触发 ——
                // 加这个观察点区分「controller 没进页面」还是「可用性门在别处」。
                clazz.declaredMethods.filter { it.name == "displayPreference" }
                    .forEach { m ->
                        m.isAccessible = true
                        module.hook(m).intercept { chain ->
                            Log.i(TAG, "displayPreference 被调用: ${cls.substringAfterLast('.')}")
                            chain.proceed()
                        }
                        Log.i(TAG, "installed: ${cls.substringAfterLast('.')} displayPreference 观察")
                    }
            }.onFailure {
                Log.w(TAG, "install ANC failed ${cls.substringAfterLast('.')}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        // ── 观察点：降噪行的触发 Runnable（是否被调度）────
        // 反汇编确认 displayPreference 的唯一调用链是这两个 lambda：
        //   DeviceProfilesSettings$$ExternalSyntheticLambda2.run → M2([B) → j3 → displayPreference
        //   DeviceProfilesSettings$a$a.run                      → P2(String) → b3 → displayPreference
        val runnables = listOf(
            "com.android.settings.bluetooth.DeviceProfilesSettings\$\$ExternalSyntheticLambda2",
            "com.android.settings.bluetooth.DeviceProfilesSettings\$a\$a"
        )
        for (cls in runnables) {
            runCatching {
                val m = findClass(cls).getDeclaredMethod("run").apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    Log.w(TAG, "降噪 Runnable 被执行: ${cls.substringAfterLast('.')}")
                    chain.proceed()
                }
                Log.i(TAG, "installed: 降噪 Runnable 观察 ${cls.substringAfterLast('.')}")
            }.onFailure {
                Log.w(TAG, "install Runnable failed ${cls.substringAfterLast('.')}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        // ── 最终触发点：DeviceProfilesSettings$a.onGetDataSucceed ─────────
        // 反汇编：DeviceProfilesSettings.<init> 偏移0034 new DeviceProfilesSettings$a
        //   $a 实现数据监听：onGetDataFailed(String)/onGetDataSucceed(String)/onSetConfig*
        //   onGetDataSucceed 内 new $a$a → P2(String) → b3 → NoiseControlController.displayPreference
        // 数据查询失败(2001) 时它永不触发 → 降噪行永不出现。
        // 策略：捕获 $a 实例 → 观察自然触发 → 页面 onCreate 后延迟主动调用一次。
        runCatching {
            val fragCls = findClass("com.android.settings.bluetooth.DeviceProfilesSettings")
            val listenerCls = findClass("com.android.settings.bluetooth.DeviceProfilesSettings\$a")

            // 1) 捕获实例（<init> 里 new 的那个）
            val ctor = listenerCls.declaredConstructors.first()
            ctor.isAccessible = true
            module.hook(ctor).intercept { chain ->
                capturedListener = chain.thisObject
                Log.i(TAG, "捕获 \$a 实例 @${System.identityHashCode(chain.thisObject)}")
                chain.proceed()
            }

            // 2) 观察自然触发
            val ok = listenerCls.getDeclaredMethod("onGetDataSucceed", String::class.java)
                .apply { isAccessible = true }
            module.hook(ok).intercept { chain ->
                Log.w(TAG, "onGetDataSucceed 自然触发 arg=${chain.args.getOrNull(0)}")
                chain.proceed()
            }

            // 3) 页面 onCreate 完成后，延迟主动触发
            val oc = fragCls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            module.hook(oc).intercept { chain ->
                val result = chain.proceed()
                // 假设：getArguments()==null 时 fragment 在 0065 已 finish，成为死对象，
                // 后续 onGetDataSucceed 全是空操作（解释「4 次触发都无下游」的不稳定性）。
                runCatching {
                    val frag = chain.thisObject
                    val args = frag?.javaClass?.getMethod("getArguments")?.invoke(frag)
                    val act = runCatching {
                        frag?.javaClass?.getMethod("getActivity")?.invoke(frag)
                    }.getOrNull()
                    val finishing = runCatching {
                        act?.javaClass?.getMethod("isFinishing")?.invoke(act)
                    }.getOrNull()
                    Log.w(TAG, "DeviceProfilesSettings: args=${if (args == null) "NULL" else "ok($args)"} activityFinishing=$finishing")
                }.onFailure {
                    Log.w(TAG, "读取 fragment 状态失败: ${it.javaClass.simpleName}")
                }
                // 面板渲染不稳定（21:57/22:05 有、22:07 后无），单次 postDelayed(1500)
                // 容易落在数据流之外。改成多个时间点重复触发，提高命中率。
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                for (delay in longArrayOf(400L, 1200L, 2500L, 4500L)) {
                    handler.postDelayed({
                        val inst = capturedListener
                        if (inst == null) {
                            Log.w(TAG, "主动触发失败(delay=${delay}ms)：未捕获 \$a 实例")
                        } else {
                            runCatching {
                                Log.w(TAG, "主动触发 onGetDataSucceed(delay=${delay}ms)")
                                ok.invoke(inst, "18:5C:A1:52:10:36")
                            }.onFailure {
                                Log.w(TAG, "主动触发抛异常(delay=${delay}ms): ${it.javaClass.simpleName}")
                            }
                        }
                    }, delay)
                }
                // 设跳过期避免主动触发期间隐式 ANC 下发（进页面时耳机不应自动切模式）
                skipAncDispatch.set(true)
                handler.postDelayed({ skipAncDispatch.set(false) }, 5500L)
                // ★ bug修复：进入蓝牙界面时向 bluetooth 发**只读查询**（QUERY_ANC 查询帧，
                //   不是调模式包）→ 耳机回真实模式 → ACTION_PODS_ANC_CHANGED 广播 →
                //   下方 receiver 更新面板选中（显示耳机本体当前模式）。
                runCatching {
                    val app = Class.forName("android.app.ActivityThread")
                        .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                    app?.sendBroadcast(Intent("com.redwind.magicorig.ACTION_REFRESH_STATUS"))
                    Log.i(TAG, "→→ 进页面已发只读查询 ACTION_REFRESH_STATUS")
                }.onFailure { Log.w(TAG, "查询广播失败: ${it.javaClass.simpleName}") }
                // ── 恢复上次使用的档位（只改 UI 选中态，不下发给耳机）──
                // ★ bug3 修复：Q 会同步回调 listener.onMultiStateClicked → 广播下发。
                //   原实现 skip 窗口 5.5s 关、Q 在 6.0s 调 —— 0.5s 间隙里广播不被拦
                //   → 进页面自动切到默认降噪。这里把 Q 包进新的 skip 窗口。
                // ★ 数据源升级：优先 magicorig_current_anc（耳机本体真实模式，由查询回包
                //   更新），无记录再回退 magicorig_last_anc（用户默认档位）。
                handler.postDelayed({
                    skipAncDispatch.set(true)
                    runCatching {
                        val app = Class.forName("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                            ?: return@runCatching
                        val cur = android.provider.Settings.Global.getString(
                            app.contentResolver, "magicorig_current_anc"
                        )
                        val last = cur
                            ?: android.provider.Settings.Global.getString(
                                app.contentResolver, "magicorig_last_anc"
                            ) ?: return@runCatching
                        Log.i(TAG, "恢复档位数据源: ${if (cur != null) "真实(current_anc)" else "记忆(last_anc)"} = $last")
                        // ★ 观察：面板三个条目的绑定协议值（Q(byte) 高亮条件 = 条目.b == 传入值）
                        //   —— 之前按 AncMode 协议字节猜（0x10/0x03/0x02/0x01/0x00），实测
                        //   Q(0x00) 不高亮"关闭"钮（全灰）→ 猜错，需要真实绑定值。
                        runCatching {
                            val pref0 = noiseListener ?: return@runCatching
                            val listField = pref0.javaClass.getDeclaredField("a").apply { isAccessible = true }
                            @Suppress("UNCHECKED_CAST")
                            val items = listField.get(pref0) as? List<Any?> ?: return@runCatching
                            val names = ArrayList<String>()
                            for (it in items) {
                                if (it == null) continue
                                val bv = it.javaClass.getDeclaredField("b").apply { isAccessible = true }.getByte(it)
                                names.add("0x" + "%02X".format(bv))
                            }
                            Log.w(TAG, "★ 面板条目绑定协议值: [${names.joinToString(", ")}]（顺序=降噪/透传/关闭）")
                        }.onFailure { Log.w(TAG, "读取面板条目失败: ${it.javaClass.simpleName}: ${it.message}") }
                        // status → 面板绑定字节（实测面板：降噪=0x01 透传=0x02 关闭=0x00）
                        val modeByte: Byte = when (last) {
                            "5", "4", "3" -> 0x01   // 降噪类 → 降噪钮
                            "2" -> 0x02              // 通透 → 透传钮
                            "1" -> 0x00              // 关闭 → 关闭钮
                            "6" -> 0x01              // 抗风噪归降噪钮
                            else -> return@runCatching
                        }
                        val pref = noiseListener
                        if (pref == null) {
                            Log.w(TAG, "恢复上次档位失败：未捕获 MultiState 偏好")
                            return@runCatching
                        }
                        pref.javaClass.getDeclaredMethod("Q", Byte::class.javaPrimitiveType)
                            .apply { isAccessible = true }
                            .invoke(pref, modeByte)
                        Log.i(TAG, "已恢复上次档位 UI: status=$last → modeByte=0x${"%02X".format(modeByte)}")
                    }.onFailure { Log.w(TAG, "恢复上次档位失败: ${it.javaClass.simpleName}: ${it.message}") }
                    // Q 同步触发 onMultiStateClicked（回调随 invoke 同步发生），
                    // 800ms 兜底覆盖可能的异步路径后关闭 skip 窗口
                    handler.postDelayed({ skipAncDispatch.set(false) }, 800L)
                }, 6000L)
                result
            }
            Log.i(TAG, "installed: onGetDataSucceed 观察 + 多点主动触发")
        }.onFailure {
            Log.w(TAG, "install onGetDataSucceed failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── ★ 最终开关：x7.a.r():boolean ─────────────────────────────────
        // 反汇编 NoiseControlController.displayPreference：
        //   0004 invoke-virtual Lx7/a;.r:()Z
        //   0008 if-nez v0 → 000b（继续）
        //   000a goto 0031                    ← false 直接 return，preference 永不 add
        //   000b super.displayPreference
        //   0012 buildPreferenceCategory → addPreference(category)
        //   0021 buildPreference + initExtras → addPreference(preference)
        // mActiveDevice 是 x7.a（活跃蓝牙设备），r() 判定「是否支持降噪」。
        runCatching {
            val m = findClass("x7.a").getDeclaredMethod("r").apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val original = chain.proceed()
                if (original == true) original
                else {
                    Log.w(TAG, "x7.a.r() => false  强制改写为 true（降噪行最终开关）")
                    true
                }
            }
            Log.i(TAG, "installed: x7.a.r() [NoiseControlController 降噪行最终开关]")
        }.onFailure {
            Log.w(TAG, "install x7.a.r failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── 观察点：Preference.setEnabled —— 降噪按钮是否被禁用 ──
        // 实测点击 [降噪] 无任何下发日志，怀疑按钮 isEnabled=false。
        // 直接观察 setEnabled 比反汇编更可靠（dexdump 对该两个私有方法输出格式不一致）。
        runCatching {
            val m = findClass("androidx.preference.Preference")
                .getDeclaredMethod("setEnabled", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val key = runCatching {
                    chain.thisObject?.javaClass?.getMethod("getKey")?.invoke(chain.thisObject)
                }.getOrNull()
                val value = chain.args.getOrNull(0)

                // 实测三档降噪按钮被显式 setEnabled(false)，导致点击无任何下发：
                //   key_denoise(false) / key_hear_through(false) / key_denoise_close(false)
                if (key in NOISE_KEYS && value == false) {
                    if (noiseEnableGuard.get()) return@intercept chain.proceed()
                    Log.w(TAG, "强制启用降噪按钮 key=$key")
                    noiseEnableGuard.set(true)
                    try {
                        val r = chain.proceed()
                        m.invoke(chain.thisObject, true)   // 置回 true
                        r
                    } finally {
                        noiseEnableGuard.set(false)
                    }
                } else {
                    chain.proceed()
                }
            }
            Log.i(TAG, "installed: Preference.setEnabled 观察 + 降噪按钮强制启用")
        }.onFailure {
            Log.w(TAG, "install setEnabled failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // 注意：setNoiseControlFunction (n3.a.u) 的归属是 AudioAccessoryManager 而非 Settings，
        // 已移到 HonorEarphoneHook（这里放会报 ClassNotFoundException: d3.f）。
        // ── 点击是否进入 preference 层（判据：getOnClickHandler/performClick）──
        // 三档按钮 key 在 Settings 进程（setEnabled 日志来自 Settings），
        // 但下发链 g2.q/n3.a 在 AAM 进程 —— 需先确认点击事件在哪一层断掉。
        runCatching {
            val m = findClass("androidx.preference.Preference")
                .getDeclaredMethod("performClick").apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val key = runCatching {
                    chain.thisObject?.javaClass?.getMethod("getKey")?.invoke(chain.thisObject)
                }.getOrNull()
                Log.w(TAG, "performClick key=$key")
                chain.proceed()
            }
            Log.i(TAG, "installed: Preference.performClick 观察")
        }.onFailure {
            Log.w(TAG, "install performClick failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── 下发链同时装进 Settings（点击发生在本进程，之前只装在 AAM）──
        for ((clsName, mName) in listOf(Pair("g2.q", "C"), Pair("g2.q", "A0"))) {
            runCatching {
                val clazz = findClass(clsName)
                val ms = clazz.declaredMethods.filter { it.name == mName }
                if (ms.isEmpty()) throw NoSuchMethodException("$clsName.$mName")
                ms.forEach { m ->
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        Log.w(TAG, "下发链命中(Settings): $clsName.$mName(${chain.args.joinToString()})")
                        chain.proceed()
                    }
                }
                Log.i(TAG, "installed: 下发链观察(Settings) $clsName.$mName (${ms.size})")
            }.onFailure {
                Log.w(TAG, "install(Settings) $clsName.$mName failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        // ── View 层点击观察（降噪是 widget，不走 Preference.performClick）──
        // widget 节点：com.android.settings:id/switch_1  clickable=true enabled=true
        runCatching {
            val vp = Class.forName("android.view.View")
                .getDeclaredMethod("performClick").apply { isAccessible = true }
            module.hook(vp).intercept { chain ->
                val v = chain.thisObject
                val id = runCatching { v.javaClass.getMethod("getId").invoke(v) as? Int }.getOrNull()
                var name: String? = null
                if (id != null && id != 0) {
                    name = runCatching {
                        v.javaClass.getMethod("getResources").invoke(v)
                            ?.javaClass?.getMethod("getResourceName", Int::class.javaPrimitiveType)
                            ?.invoke(v, id) as? String
                    }.getOrNull()
                }
                // 全量记录（不再只记有 resource-id 的）——上一轮盲区：降噪 widget 可能无 id
                val bounds = runCatching {
                    v.javaClass.getMethod("getWidth").invoke(v).toString() + "x" +
                        v.javaClass.getMethod("getHeight").invoke(v)
                }.getOrNull()
                val cls = v.javaClass.name
                if (id != null && id != 0) {
                    Log.w(TAG, "View.performClick id=$id name=$name cls=$cls")
                } else {
                    Log.i(TAG, "View.performClick (无id) cls=$cls size=$bounds")
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: View.performClick 全量观察")
        }.onFailure {
            Log.w(TAG, "install View.performClick failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── 谁给降噪 widget 绑 onClick（判断 handler 是否缺失）──
        runCatching {
            val m = Class.forName("android.view.View")
                .getDeclaredMethod("setOnClickListener",
                    Class.forName("android.view.View\$OnClickListener"))
                .apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val v = chain.thisObject
                val id = runCatching { v.javaClass.getMethod("getId").invoke(v) as? Int }.getOrNull()
                var name: String? = null
                if (id != null && id != 0) {
                    name = runCatching {
                        v.javaClass.getMethod("getResources").invoke(v)
                            ?.javaClass?.getMethod("getResourceName", Int::class.javaPrimitiveType)
                            ?.invoke(v, id) as? String
                    }.getOrNull()
                }
                val listener = chain.args.getOrNull(0)
                if (name == null || name.contains("switch_1") || name.contains("noise", true)) {
                    Log.w(TAG, "setOnClickListener id=${name ?: "(无id)"} listener=${listener?.javaClass?.name} cls=${v.javaClass.name}")
                }
                // 捕获降噪控件的 (listener, view)，主动调用 onClick —— 彻底绕过坐标猜测
                if (listener != null &&
                    listener.javaClass.name.contains("MultiStateSwitchingPanelPreference") &&
                    noiseListener == null
                ) {
                    noiseListener = listener
                    noiseView = v
                    Log.w(TAG, "★ 捕获 MultiState listener=${System.identityHashCode(listener)} view=${System.identityHashCode(v)}")
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: setOnClickListener 观察（降噪相关才记）")
        }.onFailure {
            Log.w(TAG, "install setOnClickListener failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 主动触发降噪 onClick（页面 onCreate 后 3 秒，绕过坐标）──
        runCatching {
            val fragCls = findClass("com.android.settings.bluetooth.DeviceProfilesSettings")
            val oc2 = fragCls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
            module.hook(oc2).intercept { chain ->
                val r = chain.proceed()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val L = noiseListener
                    val V = noiseView
                    if (L == null || V == null) {
                        Log.w(TAG, "主动 onClick 失败：未捕获 listener/view")
                    } else {
                        runCatching {
                            Log.w(TAG, "★ 主动调用 MultiState.onClick(view)")
                            L.javaClass.getDeclaredMethod("onClick", Class.forName("android.view.View"))
                                .apply { isAccessible = true }
                                .invoke(L, V)
                        }.onFailure {
                            Log.w(TAG, "主动 onClick 抛异常: ${it.javaClass.simpleName}: ${it.message}")
                        }
                    }
                }, 3000L)
                r
            }
            Log.i(TAG, "installed: 主动触发 MultiState.onClick")
        }.onFailure {
            Log.w(TAG, "install 主动 onClick failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── 降噪三态开关本体：MultiStateSwitchingPanelPreference ──
        // 方法表：onClick(View) / Q(B)V(设置档位, Byte=模式) / U(V) V(V) 视觉 / R(a) 注册
        runCatching {
            val cls = findClass("com.android.settings.preference.MultiStateSwitchingPanelPreference")
            val onClick = cls.getDeclaredMethod("onClick", Class.forName("android.view.View"))
                .apply { isAccessible = true }
            module.hook(onClick).intercept { chain ->
                Log.w(TAG, "MultiStateSwitchingPanel.onClick view=${chain.args.getOrNull(0)?.javaClass?.name}")
                chain.proceed()
            }
            Log.i(TAG, "installed: MultiStateSwitchingPanel.onClick")
        }.onFailure {
            Log.w(TAG, "install onClick failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching {
            val cls = findClass("com.android.settings.preference.MultiStateSwitchingPanelPreference")
            val q = cls.getDeclaredMethod("Q", Byte::class.javaPrimitiveType).apply { isAccessible = true }
            module.hook(q).intercept { chain ->
                Log.w(TAG, "★ 档位设置 Q(mode=${chain.args.getOrNull(0)})")
                chain.proceed()
            }
            Log.i(TAG, "installed: MultiStateSwitchingPanel.Q(byte) [档位]")
        }.onFailure {
            Log.w(TAG, "install Q failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── ★★ 真正的下发回调：MultiStateSwitchingPanelPreference$a.onMultiStateClicked(int, byte) ──
        // onClick 反汇编（classes3.dex hdr=222319）：
        //   tag=v.getTag(); null→return; cb=this.b; null→return;
        //   index=((Integer)tag).intValue()
        //   Log.i("MultiStateSwitchingPane","onClick: "+index)
        //   item=this.a.get(index) → $b ; mode=$b.a(item):B
        //   this.Q(B) ; cb.onMultiStateClicked(index, mode)   ← ★ 下发入口
        runCatching {
            val cls = findClass("com.android.settings.preference.MultiStateSwitchingPanelPreference\$a")
            val ms = cls.declaredMethods.filter { it.name == "onMultiStateClicked" }
            if (ms.isEmpty()) throw NoSuchMethodException("onMultiStateClicked")
            for (m in ms) {
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    Log.w(TAG, "★★ 下发回调 onMultiStateClicked index=${chain.args.getOrNull(0)} mode=${chain.args.getOrNull(1)}")
                    chain.proceed()
                }
            }
            Log.i(TAG, "installed: onMultiStateClicked 下发回调 (${ms.size})")
        }.onFailure {
            Log.w(TAG, "install onMultiStateClicked failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── 抓 onMultiStateClicked 的实现类（$a 是接口，hook 不上）──
        // 反汇编：R(Lcom/.../MultiStateSwitchingPanelPreference$a;)V 给字段 b 赋值
        runCatching {
            val cls = findClass("com.android.settings.preference.MultiStateSwitchingPanelPreference")
            val r = cls.declaredMethods.first { it.name == "R" }.apply { isAccessible = true }
            module.hook(r).intercept { chain ->
                val impl = chain.args.getOrNull(0)
                val cname = impl?.javaClass?.name
                Log.w(TAG, "★ 注册 onMultiStateClicked 实现类 = $cname  obj=${impl?.let { System.identityHashCode(it) }}")
                if (cname != null && cname != "com.android.settings.preference.MultiStateSwitchingPanelPreference\$a") {
                    noiseDispatchImpl = impl
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: MultiState.R(a) [抓实现类]")
        }.onFailure {
            Log.w(TAG, "install R failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        // ── 抓到实现类后立刻 hook 它的 onMultiStateClicked ──
        runCatching {
            val cls = findClass("com.android.settings.preference.MultiStateSwitchingPanelPreference")
            val r = cls.declaredMethods.first { it.name == "R" }.apply { isAccessible = true }
            module.hook(r).intercept { chain ->
                val impl = chain.args.getOrNull(0)
                if (impl != null && noiseDispatchHooked == false) {
                    runCatching {
                        val ms = impl.javaClass.declaredMethods.filter { it.name == "onMultiStateClicked" }
                        for (m in ms) {
                            m.isAccessible = true
                            module.hook(m).intercept { c2 ->
                                val index = c2.args.getOrNull(0)
                                val mode = c2.args.getOrNull(1)
                                Log.w(TAG, "★★ 下发回调实现类 ${impl.javaClass.name} index=$index mode=$mode")
                                // ── 协议桥：Settings 进程 → bluetooth 进程 RfcommController ──
                                // 接收端已存在：RfcommController.kt:403
                                //   ACTION_ANC_SELECT -> setANCMode(getIntExtra("status",0))
                                //   1=ANC_OFF 2=ANC_TRANSPARENT 3=ANC_NORMAL 4=ANC_DEEP 5=ANC_EXPERIMENT
                                // ★ 档位→模式映射（bug修复）：蓝牙界面三钮实际是
                                //   「降噪 / 通透 / 关闭」，此前误写成 实验性/深度/普通（1→4、2→3），
                                //   点"通透"实发深度降噪、点"关闭"实发普通降噪。正确为：
                                val status = when (index) {
                                    0 -> 5   // 降噪钮 → 实验性降噪 ANC_EXPERIMENT (0x10)
                                    1 -> 2   // 通透钮 → ANC_TRANSPARENT
                                    2 -> 1   // 关闭钮 → ANC_OFF
                                    else -> null
                                }
                                if (status != null) {
                                    // 记录用户选择的 ANC 档位到 Settings.Global（跨进程持久化）
                                    // ★ 仅降噪类档位(≥3)参与"默认降噪档位"记忆 ——
                                    //   若把 1(关)/2(通透)写进 last_anc，设备中心点「降噪」
                                    //   会回放成关闭/通透（档位记忆被污染）。
                                    if (status >= 3) runCatching {
                                        val app = Class.forName("android.app.ActivityThread")
                                            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                                        app?.let {
                                            android.provider.Settings.Global.putString(
                                                it.contentResolver, "magicorig_last_anc", status.toString()
                                            )
                                        }
                                    }.onFailure { /* 记录失败不影响广播 */ }
                                    runCatching {
                                        val app = Class.forName("android.app.ActivityThread")
                                            .getDeclaredMethod("currentApplication").invoke(null)
                                        val ctx = app as? android.content.Context
                                        if (ctx == null) throw IllegalStateException("context null")
                                        val intent = android.content.Intent(
                                            "com.redwind.magicorig.ACTION_ANC_SELECT"
                                        ).putExtra("status", status)
                                        ctx.sendBroadcast(intent)
                                        Log.w(TAG, "→→ 已广播 ANC status=$status (index=$index mode=$mode)")
                                    }.onFailure {
                                        Log.w(TAG, "广播失败: ${it.javaClass.simpleName}: ${it.message}")
                                    }
                                } else {
                                    Log.w(TAG, "index=$index 无法映射，跳过广播")
                                }
                                c2.proceed()
                            }
                        }
                        noiseDispatchHooked = true
                        Log.i(TAG, "installed: onMultiStateClicked 实现 ${impl.javaClass.name} (${ms.size})")
                    }.onFailure {
                        Log.w(TAG, "hook 实现类失败: ${it.javaClass.simpleName}: ${it.message}")
                    }
                }
                chain.proceed()
            }
        }.onFailure { Log.w(TAG, "install R#2 failed: ${it.javaClass.simpleName}") }
    }

    private var noiseDispatchImpl: Any? = null
    private var noiseDispatchHooked = false
    private val NOISE_KEYS = setOf("key_denoise", "key_hear_through", "key_denoise_close")
    private val noiseEnableGuard = ThreadLocal.withInitial { false }
    private var capturedListener: Any? = null
    private var noiseListener: Any? = null
    @Volatile private var ancReceiverRegistered = false

    /**
     * 注册 ACTION_PODS_ANC_CHANGED 接收器（真实档位实时显示）。
     * onHook 时刻 currentApplication 可能未就绪 → app 为 null 时延迟 1s 重试（最多 retryLeft 次）。
     */
    private fun registerAncReceiver(retryLeft: Int) {
        if (ancReceiverRegistered) return
        val app = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull()
        if (app == null) {
            if (retryLeft > 0) {
                Log.w(TAG, "app 未就绪，1s 后重试注册 ANC receiver（剩 $retryLeft 次）")
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ runCatching { registerAncReceiver(retryLeft - 1) } }, 1000L)
            } else {
                Log.w(TAG, "app 仍未就绪，放弃注册 ANC receiver（6s 恢复兜底仍可用）")
            }
            return
        }
        val rc = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val status = intent?.getIntExtra("status", -1) ?: -1
                // ★ status → 面板绑定字节（运行时实测：降噪=0x01 透传=0x02 关闭=0x00，
                //   与 SPP 协议字节 0x10/0x01/0x00 不同 —— 曾按协议字节猜导致 Q 不高亮）
                val b: Byte = when (status) {
                    3, 4, 5, 6 -> 0x01   // 降噪类（含抗风噪）→ 降噪钮
                    2 -> 0x02            // 通透 → 透传钮
                    1 -> 0x00            // 关闭 → 关闭钮
                    else -> return
                }
                Log.w(TAG, "★ 收到耳机真实 ANC 状态=$status → 面板选中更新")
                skipAncDispatch.set(true)   // 防 Q 触发隐式下发
                runCatching {
                    val pref = noiseListener ?: return@runCatching
                    pref.javaClass.getDeclaredMethod("Q", Byte::class.javaPrimitiveType)
                        .apply { isAccessible = true }.invoke(pref, b)
                }.onFailure { Log.w(TAG, "面板更新失败: ${it.javaClass.simpleName}") }
                // Q 同步回调 onMultiStateClicked（已 skip 拦截），800ms 后关窗
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ skipAncDispatch.set(false) }, 800L)
                // 持久化（下次进页面 6s 恢复兜底也用真实值）
                runCatching {
                    ctx?.contentResolver?.let {
                        android.provider.Settings.Global.putString(it, "magicorig_current_anc", status.toString())
                    }
                }
            }
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= 33) 0x2 else 0  // RECEIVER_EXPORTED=0x2
        app.registerReceiver(
            rc,
            android.content.IntentFilter("com.redwind.magicorig.ACTION_PODS_ANC_CHANGED"),
            flags
        )
        ancReceiverRegistered = true
        Log.i(TAG, "registered: ACTION_PODS_ANC_CHANGED → 真实档位实时显示")
    }
    private var noiseView: Any? = null
    private var skipAncDispatch = ThreadLocal.withInitial { false }
}
