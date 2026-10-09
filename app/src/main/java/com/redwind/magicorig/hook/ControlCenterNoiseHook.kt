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
                val modeCls = Class.forName(MODE_CLS)
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
                // ncValue → 模块 status：降噪=用户默认档位(5/4/3)  透传=2  关闭=1  自适应忽略
                val status = when (nc) {
                    1 -> readLastAnc() ?: "5"
                    2 -> "2"
                    0 -> "1"
                    else -> null
                }
                Log.w(TAG, "★ setNoiseCtrlMode(deviceId=$deviceId, nc=$nc) → status=$status")
                if (status != null) {
                    runCatching {
                        val app = Class.forName("android.app.ActivityThread")
                            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
                        app?.sendBroadcast(
                            Intent("com.redwind.magicorig.ACTION_ANC_SELECT").putExtra("status", status)
                        )
                        Log.w(TAG, "→→ 已广播 ANC status=$status (设备中心)")
                    }.onFailure { Log.w(TAG, "广播失败: ${it.javaClass.simpleName}") }
                } else {
            Log.w(TAG, "nc=$nc 不支持，跳过广播")
                }
                // 保留原调用：让荣耀回调（onResult/onError）正常触发，复位 isSetting/高亮
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
    }

    private const val OUR_MAC = "18:5C:A1:52:10:36"
    private const val OUR_NAME = "原道 OriG in"

    /** 读 Settings.Global["magicorig_last_anc"]（bluetooth 进程写入的用户默认档位） */
    private fun readLastAnc(): String? = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? android.content.Context
            ?: return null
        android.provider.Settings.Global.getString(app.contentResolver, "magicorig_last_anc")
    }.getOrNull()
}
