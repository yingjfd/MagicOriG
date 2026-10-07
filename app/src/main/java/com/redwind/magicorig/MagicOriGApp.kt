package com.redwind.magicorig

import android.app.Application
import android.util.Log

/**
 * MagicOriG Application 入口。
 *
 * 本模块基于 HyperOriG（KiriChen-Wind/HyperOriG）移植，
 * 目标系统为华为 MagicOS / HarmonyOS NEXT。
 *
 * 适配差异（vs HyperOriG）：
 * 1. 包名由 com.redwind.hyperorig → com.redwind.magicorig
 * 2. 作用域使用标准 Android 包名（MagicOS 保留 AOSP 蓝牙栈）
 *    - com.android.bluetooth     — 蓝牙服务
 *    - com.android.systemui      — 状态栏图标
 *    - com.android.settings      — 设置页面
 * 3. 移除 MiLinkServiceHook（MagicOS 无 MiLink）
 * 4. 移除 FocusIsland 依赖（MagicOS 无灵动岛特性）
 * 5. 移除 MiuiStrongToast（非 HyperOS）
 */
class MagicOriGApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MagicOriGApp", "onCreate")
    }
}
