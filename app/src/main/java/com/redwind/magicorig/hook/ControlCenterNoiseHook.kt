package com.redwind.magicorig.hook

import android.content.Intent

/**
 * ControlCenterNoiseHook — 作用域 com.hihonor.controlcenter（设备中心/控制中心降噪卡）。
 *
 * 逆向结论（discovery/cc/dn/classes.dex）：
 * - DeviceServiceCardDataMgr.createEarphoneNoiseSection(LinkDevice) 无条件建卡（唯一退出=LinkDevice null）
 *   数据来自 EarphoneNoiseData.fromBatteryData(BatteryData)
 *   返回 null → supportedModes=空 + currentMode=UNKNOWN → 按钮全灰（我们的耳机现状）
 * - 点击 EarphoneNoiseSectionController.onNoiseModeClick(NoiseMode)：
 *     4个早退（section禁用/isSetting/佩戴禁用/已是当前模式）
 *     → ncValue = EarphoneNoiseData.toNcModeValue(mode)
 *     → PropertyUtils.setNoiseCtrlMode(deviceId, ncValue, cb)   ← 下发接口
 *
 * ncValue 语义（由 toNcModeValue 推定）：1=降噪 2=透传(AWARENESS) 0=关闭 3=自适应
 *
 * 本 hook 做两件事：
 * 1. fromBatteryData 返回 null 时注入（支持 噪声/感知/关闭 三模式 + 按 last_anc 恢复当前档）
 *    —— 原返回非 null 时用原值，不影响荣耀自家耳机
 * 2. setNoiseCtrlMode 拦截 → 映射为本模块 status → 广播 ACTION_ANC_SELECT
 *    → 复用已打通的 bluetooth 进程 RfcommController → SPP 0x4E 链路
 */
object ControlCenterNoiseHook : HookContext() {
    private const val TAG = "MagicOriG-CtrlCenter"
    @Volatile private var lastClickStackAt = 0L
    private const val MODE_CLS = "com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseData\$NoiseMode"
    private const val DATA_CLS = "com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseData"

    override fun onHook() {
        // ── 1) 按钮点亮：fromBatteryData 为 null 时注入数据 ──
        runCatching {
            val dataCls = findClass(DATA_CLS)
            val batCls = findClass("com.hihonor.controlviewnew.info.BatteryData")
            val fb = dataCls.getDeclaredMethod("fromBatteryData", batCls).apply { isAccessible = true }
            module.hook(fb).intercept { chain ->
                val orig = chain.proceed()
                if (orig != null) return@intercept orig   // 有真实数据 → 不干预
                // 构造注入对象
                val data = dataCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                // ★ 必须用 findClass（宿主 ClassLoader）：Class.forName 走模块 loader 会 CNFE
                //   （实测: ClassNotFoundException EarphoneNoiseData$NoiseMode，DexPathList 含模块 apk）
                val modeCls = findClass(MODE_CLS)
                val consts = modeCls.enumConstants
                if (consts == null) {
                    Log.w(TAG, "NoiseMode 非 enum？跳过注入")
                    return@intercept data
                }
                val wanted = setOf("NOISE_CANCELLATION", "AWARENESS", "OFF")
                val modes = java.util.ArrayList<Any>()
                for (c in consts) {
                    if (c.toString() in wanted) modes.add(c)
                }
                dataCls.getDeclaredMethod("setSupportedModes", List::class.java)
                    .apply { isAccessible = true }.invoke(data, modes)
                // 从 Settings.Global 恢复上次档位（5/4/3→降噪细分，1→关，2→透传）
                val last = readLastAnc()
                val cur = when (last) {
                    "1" -> "OFF"
                    "2" -> "AWARENESS"
                    else -> "NOISE_CANCELLATION"
                }
                val curMode = consts.firstOrNull { it.toString() == cur }
                if (curMode != null) {
                    dataCls.getDeclaredMethod("setCurrentMode", modeCls)
                        .apply { isAccessible = true }.invoke(data, curMode)
                }
                Log.i(TAG, "fromBatteryData 注入成功: modes=$modes current=$cur (orig=null)")
                data
            }
            Log.i(TAG, "installed: EarphoneNoiseData.fromBatteryData [按钮点亮]")
        }.onFailure {
            Log.w(TAG, "install fromBatteryData failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 2) 下发桥：setNoiseCtrlMode → 广播 → 已打通 SPP 链路 ──
        runCatching {
            val pu = findClass("com.hihonor.controlviewnew.utils.PropertyUtils")
            val m = pu.declaredMethods.first { it.name == "setNoiseCtrlMode" }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val deviceId = chain.args.getOrNull(0)
                val nc = (chain.args.getOrNull(1) as? Int) ?: -1
                // ncValue → 模块 status(非空 Int)：降噪=用户默认档位(5/4/3)  透传=2  关闭=1  自适应忽略
                // ★ 必须是 Int：曾用 String 导致 bluetooth 端 getIntExtra("status",0) 读不到
                //   （类型不符返回默认 0，0 不在映射表 → 不发 SPP 帧 → 点击无效）
                val status: Int = when (nc) {
                    1 -> (readLastAnc() ?: "5").toIntOrNull() ?: 5
                    2 -> 2
                    0 -> 1
                    else -> -1
                }
                Log.w(TAG, "★ setNoiseCtrlMode(deviceId=$deviceId, nc=$nc) → status=$status")
                var handled = false
                if (status in 0..9) {
                    runCatching {
                        val app = Class.forName("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                        app?.sendBroadcast(
                            Intent("com.redwind.magicorig.ACTION_ANC_SELECT").putExtra("status", status)
                        )
                        Log.w(TAG, "→→ 已广播 ANC status=$status (设备中心)")
                    }.onFailure { Log.w(TAG, "广播失败: ${it.javaClass.simpleName}") }
                    // ★ 荣耀 MBB 对第三方设备的 setNoiseCtrlMode 永不回调（onResult/onError 都不来）
                    //   → onNoiseModeClick 的 isSetting 永真 → 点击一次后所有模式被早退（卡死）。
                    //   这里直接模拟成功回调（$1.onResult 的参数不参与逻辑，只 post V 复位+高亮），
                    //   并跳过原 MBB 调用。
                    val cb = chain.args.getOrNull(2)
                    if (cb != null) {
                        runCatching {
                            cb.javaClass.getMethod("onResult", String::class.java).invoke(cb, "")
                            handled = true
                            Log.w(TAG, "→→ 已模拟 onResult 回调（跳过 MBB 原调用）")
                        }.onFailure { Log.w(TAG, "模拟回调失败: ${it.javaClass.simpleName}: ${it.message}") }
                    }
                } else {
            Log.w(TAG, "nc=$nc 不支持，跳过广播")
                }
                if (handled) {
                    return@intercept null   // setNoiseCtrlMode 返回 void
                }
                // 回退路径：仍走原调用（依赖其回调）
                chain.proceed()
            }
            Log.i(TAG, "installed: PropertyUtils.setNoiseCtrlMode [设备中心下发桥]")
        }.onFailure {
            Log.w(TAG, "install setNoiseCtrlMode failed (可能本进程无此类): ${it.javaClass.simpleName}")
        }

        // ── 3) 观察点击（4个早退排障用） ──
        runCatching {
            val ctlCls = findClass("com.hihonor.controlviewnew.deviceservicecard.EarphoneNoiseSectionController")
            val m = ctlCls.getDeclaredMethods().first {
                it.name == "onNoiseModeClick" && it.parameterCount == 1
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                Log.w(TAG, "onNoiseModeClick mode=${chain.args.getOrNull(0)}")
                // 抓调用栈定位"打开页面就自动点击"的触发者（限频：每 5 秒最多 1 条）
                val now = System.currentTimeMillis()
                if (now - lastClickStackAt > 5000) {
                    lastClickStackAt = now
                    val st = Thread.currentThread().stackTrace
                    val frames = st.drop(3).take(8)
                        .filterNot { it.className.startsWith("com.redwind.magicorig") || it.className.startsWith("java.lang.reflect") || it.className == "de.robv.android.xposed" || it.className.contains("xposed") || it.className.contains("LSPosed") || it.className.contains("lsposed") }
                        .joinToString("\n    ") { "${it.className.substringAfterLast('.')}.$it" }
                    Log.w(TAG, "  △ 调用栈:\n    $frames")
                }
                chain.proceed()
            }
            Log.i(TAG, "installed: onNoiseModeClick [观察]")
        }.onFailure {
            Log.w(TAG, "install onNoiseModeClick failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 4) 设备列表注入：耳机不在 devicemanager.getDevices() 时构造 LinkDevice JSON ──
        // getDeviceList() 唯一来源是 DeviceManager.getDevices()（nearby 提供），
        // 我们的耳机不在其中 → 设备中心不显示（用户反馈"没添加进去"的根因）。
        // LinkDevice 序列化字段（Gson）：deviceId/deviceName/hnDeviceType/deviceNodeId/
        //   macAddress/btMacAddress/prodId/isSelfDevice/hnOnLineTypeList
        // 类型枚举：Third_EarPhone（第三方耳机）
        // 纯字符串操作，不构造 AIDL 对象；已含本机则跳过，避免重复。
        runCatching {
            val provCls = findClass("com.hihonor.controlcenter.superdevice.ControlCenterProvider")
            val m = provCls.declaredMethods.first {
                it.name == "getDeviceList" && it.parameterCount == 0
            }.apply { isAccessible = true }
            module.hook(m).intercept { chain ->
                val result = chain.proceed()
                runCatching {
                    if (result == null) return@runCatching
                    val ext = result.javaClass.getMethod("getExt").invoke(result) as? String
                        ?: return@runCatching
                    if (ext.contains(OUR_MAC) || ext.contains(OUR_NAME)) {
                        Log.i(TAG, "设备列表已含原道 OriG in，跳过注入")
                        return@runCatching
                    }
                    val trimmed = ext.trim()
                    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                        Log.w(TAG, "ext 非数组，跳过: ${trimmed.take(80)}")
                        return@runCatching
                    }
                    // hnOnLineTypeList 给空数组（Gson 空数组不校验元素类型，安全）
                    val our = "{\"deviceId\":\"$OUR_MAC\"," +
                            "\"deviceName\":\"$OUR_NAME\"," +
                            "\"hnDeviceType\":\"Third_EarPhone\"," +
                            "\"deviceNodeId\":\"$OUR_MAC\"," +
                            "\"macAddress\":\"$OUR_MAC\"," +
                            "\"btMacAddress\":\"$OUR_MAC\"," +
                            "\"prodId\":\"00VVD9\"," +
                            "\"isSelfDevice\":false," +
                            "\"hnOnLineTypeList\":[]}"
                    val merged = if (trimmed.length <= 2) "[$our]"
                                 else trimmed.dropLast(1) + ",$our]"
                    result.javaClass.getMethod("setExt", String::class.java)
                        .invoke(result, merged)
                    Log.w(TAG, "★ 设备列表已注入 $OUR_NAME (Third_EarPhone)")
                }.onFailure {
                    Log.w(TAG, "getDeviceList 注入失败: ${it.javaClass.simpleName}: ${it.message}")
                }
                result
            }
            Log.i(TAG, "installed: ControlCenterProvider.getDeviceList [设备注入]")
        }.onFailure {
            Log.w(TAG, "install getDeviceList failed: ${it.javaClass.simpleName}: ${it.message}")
        }

        // ── 5) 球列表注入：设备中心（NewControlHomeActivity/控制中心）用的是
        //    DeviceBallManager 的球体系，不走 getDeviceList（实测 hook 装上但零触发）。
        //    在 getAllDeviceInfo/getDeviceInfoList 返回前 append 我们的 DeviceInfo，
        //    batteryData 保持 null → fromBatteryData 注入 hook 兜底点亮按钮。
        runCatching {
            val mgrCls = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
            val infoCls = findClass("com.hihonor.controlviewnew.info.DeviceInfo")
            val typeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
            val thirdEar = typeCls.enumConstants?.firstOrNull { it.toString() == "Third_EarPhone" }
                ?: typeCls.enumConstants?.firstOrNull()
            val ctor = infoCls.declaredConstructors.firstOrNull {
                it.parameterCount == 7 && it.parameterTypes[0] == String::class.java
            }
            if (thirdEar == null || ctor == null) throw IllegalStateException("DeviceInfo 构造/类型不可用")
            ctor.isAccessible = true

            fun buildOur(): Any? = runCatching {
                val info = ctor.newInstance(OUR_MAC, OUR_NAME, thirdEar, 0, true, false, true)
                // 7参构造不含 prodId/btMacAddress —— 球渲染按 prodId 查图标资源，
                // null 会静默跳过渲染（列表有数据、UI 不显示的根因）
                runCatching { infoCls.getMethod("setProdId", String::class.java).invoke(info, OUR_PROD) }
                runCatching { infoCls.getMethod("setBtMacAddress", String::class.java).invoke(info, OUR_MAC) }
                runCatching { infoCls.getMethod("setName", String::class.java).invoke(info, OUR_NAME) }
                runCatching { infoCls.getMethod("setIndex", Int::class.javaPrimitiveType).invoke(info, 1) }
                info
            }.getOrNull()

            var injectedCount = 0
            // getAllDeviceInfo/getDeviceInfoList=全量，getShowRemoteDeviceInfoList=UI 展示过滤出口
            for (mName in listOf("getAllDeviceInfo", "getDeviceInfoList", "getShowRemoteDeviceInfoList")) {
                val m = mgrCls.declaredMethods.firstOrNull { it.name == mName } ?: continue
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val result = chain.proceed() as? java.util.List<*>
                    if (result != null) {
                        val exists = result.any {
                            runCatching { it?.javaClass?.getMethod("getId")?.invoke(it) == OUR_MAC }.getOrDefault(false)
                        }
                        // 任务6：仅耳机在线时注入（断连后球/卡片消失）
                        if (!exists && isOurConnected()) {
                            buildOur()?.let { info ->
                                // List<*> 为只读视图，反射调 add；视图可能不可 add（unmodifiable）→ 全程保护
                                runCatching {
                                    result.javaClass.getMethod("add", Any::class.java)
                                        .invoke(result, info)
                                    injectedCount++
                                    Log.w(TAG, "★ 球列表已注入 $OUR_NAME → $mName (总数=${result.size})")
                                }.onFailure {
                                    Log.w(TAG, "$mName add 失败(视图只读?): ${it.javaClass.simpleName}")
                                }
                            }
                        }
                    }
                    result
                }
                Log.i(TAG, "installed: DeviceBallManager.$mName [球列表注入]")
            }
            // 空态由数量方法决定（"未发现其他设备"）：原值0且我们已注入球 → 至少1
            mgrCls.declaredMethods.firstOrNull { it.name == "getShowRemoteDeviceNum" }?.let { m ->
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val n = chain.proceed() as? Int ?: 0
                    val fixed = if (n <= 0 && isOurConnected()) 1 else n
                    if (fixed != n) Log.w(TAG, "★ getShowRemoteDeviceNum $n → $fixed")
                    fixed
                }
                Log.i(TAG, "installed: DeviceBallManager.getShowRemoteDeviceNum [数量修正]")
            }
            // ★ 根上修：DeviceBallManager.deviceInfoMap 无我们的设备 → getDeviceInfoById 返 null
            //   → updateDeviceOnlineStatus 空转 / createEarphoneNoiseSection 拿不到 batteryData /
            //   createDeviceServiceCard 电量查询断链。统一在查询出口兜底（缓存单例）。
            var ourInfoCache: Any? = null
            mgrCls.declaredMethods.firstOrNull { it.name == "getDeviceInfoById" }?.let { m ->
                m.isAccessible = true
                module.hook(m).intercept { chain ->
                    val id = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    if (id == OUR_MAC && ret == null) {
                        val cached = ourInfoCache ?: buildOur()?.also { ourInfoCache = it }
                        if (cached != null) {
                            Log.w(TAG, "★ getDeviceInfoById(OUR_MAC) null → 兜底 DeviceInfo")
                        }
                        cached
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: DeviceBallManager.getDeviceInfoById [map 兜底]")
            }

            // ── 决定性观察：球创建到哪一步（id / 是否返回 null） ──
            runCatching {
                val lmCls = findClass("com.hihonor.controlviewnew.widget.DeviceBallLayoutManager")
                val cmb = lmCls.declaredMethods.firstOrNull {
                    it.name == "createDeviceBall" && it.parameterCount >= 1
                }
                if (cmb != null) {
                    cmb.isAccessible = true
                    module.hook(cmb).intercept { chain ->
                        val info = chain.args.getOrNull(0)
                        val id = runCatching { info?.javaClass?.getMethod("getId")?.invoke(info) }.getOrNull()
                        val ret = chain.proceed()
                        // 在线态分流（决定电量/降噪 section 走 online 路径）在
                        // BasicDeviceBallView.isOnline()，由 devicemanager 推送驱动，
                        // 第三方耳机无此推送 → 恒 false → 走离线卡（无电量无降噪）。
                        // 球创建成功后直接把 adapter 置在线（setIsOnline 会同步到 ballView）。
                        if (id == OUR_MAC && ret != null) {
                            runCatching {
                                ret.javaClass.getMethod("setIsOnline", Boolean::class.javaPrimitiveType)
                                    .invoke(ret, true)
                                Log.w(TAG, "★ setIsOnline(true) on adapter ($OUR_NAME)")
                            }.onFailure { Log.w(TAG, "setIsOnline 失败: ${it.javaClass.simpleName}: ${it.message}") }
                            // ★ 主动驱动在线刷新：updateDeviceOnlineStatus 是 DeviceBallManager
                            //   的公开状态入口（devicemanager 推送也走它），会同步 ballView.isOnline
                            runCatching {
                                val mCls = findClass("com.hihonor.controlviewnew.manager.DeviceBallManager")
                                val inst = mCls.getMethod("getInstance").invoke(null)
                                mCls.getMethod(
                                    "updateDeviceOnlineStatus",
                                    String::class.java,
                                    Boolean::class.javaPrimitiveType
                                ).invoke(inst, OUR_MAC, true)
                                Log.w(TAG, "★ updateDeviceOnlineStatus($OUR_MAC, true)")
                            }.onFailure { Log.w(TAG, "updateDeviceOnlineStatus 失败: ${it.javaClass.simpleName}: ${it.message}") }
                        }
                        if (id == OUR_MAC || ret == null) {
                            Log.w(TAG, "▶ createDeviceBall(id=$id) → ${if (ret == null) "NULL(失败)" else "OK"}")
                        }
                        ret
                    }
                    Log.i(TAG, "installed: DeviceBallLayoutManager.createDeviceBall [球创建观察+在线态]")
                } else {
                    Log.w(TAG, "createDeviceBall 未找到")
                }
            }.onFailure { Log.w(TAG, "createDeviceBall 观察失败: ${it.javaClass.simpleName}") }

            // ── 6) 布局修正（关键门③）：
            //    createSections 里 {电量,降噪} section 的前置条件是
            //    DeviceLayoutConfig.hasSection(...)，即 selectLayout(type).layoutMask 含位。
            //    REMOTE_EARPHONE 的 mask 明确含 EARPHONE_BATTERY+EARPHONE_NOISE（clinit or 运算），
            //    但 selectLayout 的 switch 可能把 Third_EarPhone 落到 default → REMOTE_PHONE
            //    （与"卡片只有响铃/连接、无电量无降噪"实拍吻合）→ 强制归位。
            runCatching {
                val cfgCls = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceLayoutConfig")
                val sel = cfgCls.declaredMethods.first {
                    it.name == "selectLayout" && it.parameterCount >= 1
                }.apply { isAccessible = true }
                val remoteEar = cfgCls.enumConstants?.firstOrNull { it.toString() == "REMOTE_EARPHONE" }
                    ?: throw IllegalStateException("REMOTE_EARPHONE 枚举不存在")
                module.hook(sel).intercept { chain ->
                    val typeName = chain.args.getOrNull(0)?.toString()
                    val ret = chain.proceed()
                    if (typeName == "Third_EarPhone" && ret !== remoteEar) {
                        Log.w(TAG, "★ selectLayout(Third_EarPhone) $ret → REMOTE_EARPHONE")
                        remoteEar
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: DeviceLayoutConfig.selectLayout [布局修正→REMOTE_EARPHONE]")
            }.onFailure {
                Log.w(TAG, "selectLayout hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 7) 决定性观察：降噪 section 创建（区分"门③拒绝"vs"创建返回null"） ──
            runCatching {
                val mgr2 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val mNoise = mgr2.declaredMethods.filter { it.name == "createEarphoneNoiseSection" }
                val mBat = mgr2.declaredMethods.filter { it.name == "createEarphoneBatterySection" }
                for (m in mNoise + mBat) {
                    m.isAccessible = true
                    module.hook(m).intercept { chain ->
                        val dev = chain.args.getOrNull(0)
                        val devId = runCatching { dev?.javaClass?.getMethod("getDeviceId")?.invoke(dev) }.getOrNull()
                        val ret = chain.proceed()
                        Log.w(TAG, "▶ ${m.name}(deviceId=$devId) → ${if (ret == null) "null" else "SEC(" + ret.hashCode() + ")"}")
                        ret
                    }
                }
                Log.i(TAG, "installed: createEarphoneNoise/BatterySection [section 创建观察]")
            }.onFailure {
                Log.w(TAG, "section 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 7b) createSections 观察：config 布局 + 返回装配数（定位 sections=null 环节） ──
            runCatching {
                val mgr4 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val cs = mgr4.declaredMethods.first {
                    it.name == "createSections" && it.parameterCount >= 3
                }.apply { isAccessible = true }
                module.hook(cs).intercept { chain ->
                    val config = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    val size = (ret as? List<*>)?.size
                    Log.w(TAG, "▶ createSections(config=$config) → size=$size")
                    ret
                }
                Log.i(TAG, "installed: DeviceServiceCardDataMgr.createSections [装配观察]")
            }.onFailure {
                Log.w(TAG, "createSections 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 9) ★ 最后一环：genDeviceServiceInfo 开头 c2.s.o(deviceId) 查 LinkDevice，
            //    查不到 → log("linkDevice is null") → return null → createSections 永不执行
            //    （sections=null 根因）。在 c2/s 注册表查询出口兜底注入 LinkDevice。
            runCatching {
                val sCls = findClass("c2.s")
                val o = sCls.declaredMethods.first {
                    it.name == "o" && it.parameterCount == 1 &&
                            it.returnType.name.endsWith("LinkDevice")
                }.apply { isAccessible = true }
                val ldCls = findClass("com.hihonor.controlcenter_aar.bean.LinkDevice")
                val typeCls = findClass("com.hihonor.controlcenter_aar.bean.HnDeviceProductType")
                val third = typeCls.enumConstants?.firstOrNull { it.toString() == "Third_EarPhone" }
                    ?: throw IllegalStateException("Third_EarPhone 枚举不存在")
                val ctor = ldCls.declaredConstructors.firstOrNull {
                    it.parameterCount == 7 && it.parameterTypes[0] == String::class.java
                }?.apply { isAccessible = true }
                    ?: throw IllegalStateException("LinkDevice 7参构造不存在")
                var ldCache: Any? = null
                module.hook(o).intercept { chain ->
                    val id = chain.args.getOrNull(0)
                    val ret = chain.proceed()
                    if (id == OUR_MAC && ret == null) {
                        val ld = ldCache ?: runCatching {
                            // (udid, name, type, nodeId, isLocal, onlineList, prodId)
                            ctor.newInstance(
                                OUR_MAC, OUR_NAME, third, OUR_MAC,
                                false, java.util.ArrayList<Any>(), OUR_PROD
                            )
                        }.getOrNull()?.also { ldCache = it }
                        if (ld != null) Log.w(TAG, "★ c2.s.o(OUR_MAC) null → 注入 LinkDevice")
                        ld
                    } else {
                        ret
                    }
                }
                Log.i(TAG, "installed: c2.s.o [LinkDevice 注册表兜底]")
            }.onFailure {
                Log.w(TAG, "c2.s.o hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 10) ★ 在线态断根：createDeviceServiceCard 每次开卡都读 ballView.isOnline()
            //    决定走在线卡（gen 5-section 含噪声控制）还是离线卡（只有响铃/连接）。
            //    我们在球创建时设的 true 会被荣耀属性监听（onPropertyInfoChange）重置回 false
            //    → 卡片时而在线时而离线。直接在读取口对我们的球恒返回 true。
            runCatching {
                val bvCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val m = bvCls.getDeclaredMethods().first {
                    it.name == "isOnline" && it.parameterCount == 0 &&
                            it.returnType == Boolean::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed() as? Boolean ?: false
                    if (!ret) {
                        val receiver = chain.thisObject
                        val id = runCatching {
                            receiver?.javaClass?.getMethod("getDeviceId")?.invoke(receiver)
                        }.getOrNull()
                        if (id == OUR_MAC) {
                            Log.w(TAG, "★ ballView.isOnline() false → true ($OUR_NAME)")
                            return@intercept true
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: BasicDeviceBallView.isOnline [在线态断根]")
            }.onFailure {
                Log.w(TAG, "isOnline hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 11) 任务2：卡片电量 —— getBatteryLevel 对我们的球返回跨进程真实电量 ──
            //    （默认数据链 queryBatteryInfo 对第三方设备无回调 → 卡片无电量）
            runCatching {
                val bvCls = findClass("com.hihonor.controlviewnew.widget.BasicDeviceBallView")
                val m = bvCls.getDeclaredMethods().first {
                    it.name == "getBatteryLevel" && it.parameterCount == 0 &&
                            it.returnType == Int::class.javaPrimitiveType
                }.apply { isAccessible = true }
                module.hook(m).intercept { chain ->
                    val ret = chain.proceed() as? Int ?: -1
                    val receiver = chain.thisObject
                    val id = runCatching {
                        receiver?.javaClass?.getMethod("getDeviceId")?.invoke(receiver)
                    }.getOrNull()
                    if (id == OUR_MAC) {
                        val bat = batteryLevelForCard()
                        if (bat != null && bat != ret) {
                            Log.w(TAG, "★ ballView.getBatteryLevel $ret → $bat ($OUR_NAME)")
                            return@intercept bat
                        }
                    }
                    ret
                }
                Log.i(TAG, "installed: BasicDeviceBallView.getBatteryLevel [电量显示]")
            }.onFailure {
                Log.w(TAG, "getBatteryLevel hook 失败: ${it.javaClass.simpleName}: ${it.message}")
            }

            // ── 8) 全景观察：genDeviceServiceInfo 返回的 sections 构成（一次看清全卡） ──
            runCatching {
                val mgr3 = findClass("com.hihonor.controlviewnew.deviceservicecard.DeviceServiceCardDataMgr")
                val gen = mgr3.declaredMethods.first {
                    it.name == "genDeviceServiceInfo" && it.parameterCount >= 2
                }.apply { isAccessible = true }
                module.hook(gen).intercept { chain ->
                    val ret = chain.proceed()
                    runCatching {
                        val secs = ret?.javaClass?.getMethod("getServiceSections")?.invoke(ret) as? List<*>
                        val types = secs?.joinToString(",") {
                            it?.javaClass?.simpleName?.take(28) ?: "null"
                        } ?: "?"
                        val devId = chain.args.getOrNull(0)
                        Log.w(TAG, "▶ genDeviceServiceInfo(dev=$devId) sections=${secs?.size} [$types]")
                    }.onFailure { Log.w(TAG, "gen 读取失败: ${it.javaClass.simpleName}") }
                    ret
                }
                Log.i(TAG, "installed: genDeviceServiceInfo [全景观察]")
            }.onFailure {
                Log.w(TAG, "gen 观察失败: ${it.javaClass.simpleName}: ${it.message}")
            }
        }.onFailure {
            Log.w(TAG, "install DeviceBallManager 注入失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private const val OUR_MAC = "18:5C:A1:52:10:36"
    private const val OUR_NAME = "原道 OriG in"
    private const val OUR_PROD = "00VVD9"

    /** 读 Settings.Global["magicorig_last_anc"]（bluetooth 进程写入的用户默认档位） */
    /**
     * 任务6：耳机是否在线。bluetooth 进程在电量数据到达时写 "1"、断连时写 "0"
     * （Settings.Global["magicorig_connected"]）。默认/无记录 = false —— 断连后球不注入。
     */
    private fun isOurConnected(): Boolean = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return false
        android.provider.Settings.Global.getString(app.contentResolver, "magicorig_connected") == "1"
    }.getOrDefault(false)

    /** 任务2：设备中心卡片电量 —— 读跨进程电量（L90,R85）取双耳平均，无数据返回 null */
    private fun batteryLevelForCard(): Int? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        val raw = android.provider.Settings.Global.getString(app.contentResolver, "magicorig_battery")
            ?: return null
        if (!raw.contains(",")) return null
        val l = raw.substringBefore(',').removePrefix("L").toIntOrNull()
        val r = raw.substringAfter(',').removePrefix("R").toIntOrNull()
        when {
            l != null && r != null -> (l + r) / 2
            l != null -> l
            r != null -> r
            else -> null
        }
    }.getOrNull()

    private fun readLastAnc(): String? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        android.provider.Settings.Global.getString(app.contentResolver, "magicorig_last_anc")
    }.getOrNull()
}
