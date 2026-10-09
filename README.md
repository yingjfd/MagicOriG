# MagicOriG（原道 OriG in × 荣耀 MagicOS 接入）

> ## ⚠️ 本模块由 AI 生成 / This module is AI-generated
> 本项目由大语言模型（AI）在人类指导下生成并迭代维护，包括全部逆向分析、Hook 代码、
> 构建流水线与文档。代码未经人工长期审计，请自行评估风险后使用；欢迎人工审查与 PR。
>
> This project was generated and iterated by a large language model (AI) under human
> direction, including all reverse engineering, hook code, the build pipeline and these docs.
> The code has not been long-term audited by humans — evaluate the risk yourself;
> human review and PRs are welcome.

让第三方 SPP 耳机（NickHCK YuanDao OriG in）在荣耀 MagicOS 上**像荣耀自家耳机一样**使用：蓝牙设置详情页与设备中心直接调节降噪、电量显示、控制中心快捷磁贴、断联通知自清。所有降噪参数走已打通的 SPP 协议链路，点击即下发（~50ms）。

An LSPosed module that lets a third-party SPP earbud (**NickHCK YuanDao OriG in**) behave
like a first-party Honor earbud on MagicOS: adjust ANC right in Bluetooth settings and the
**device center**, show battery, control it from a Quick Settings tile, and auto-clear the
notification on disconnect. Every ANC click goes down the verified SPP link (~50ms).

> ## ⚠️ 支持范围 / Support scope
> **仅面向荣耀（HONOR）MagicOS；不为华为（HUAWEI）HarmonyOS 提供任何修改与支持。**
> Honor MagicOS only — no changes or support for Huawei HarmonyOS.
> 历史文档中出现的 "HarmonyOS" 字样均为早期描述遗留，不代表本项目支持华为系统。

## 为什么做这个模块 / Why

荣耀 MagicOS 的降噪面板、设备中心服务卡、电量体系**只为自家（及认证）耳机开放**：

1. **蓝牙设置页**：`NoiseControlController.displayPreference` 依赖设备能力判定（`x7.a.r()`），第三方 SPP 耳机直接被过滤，降噪面板根本不渲染；
2. **设备中心**：球列表/服务卡来自 `devicemanager.getDevices()`，第三方耳机不在其中 —— 想显示就卡在十几道关卡（列表出口 → 球创建 → 布局 mask → LinkDevice 注册表 → 在线态分流）；
3. **协议层**：荣耀的 `setNoiseCtrlMode` 下发走 MBB 属性通道，第三方耳机只认 NiceHCK 的 SPP `0x4E` 私有帧。

结果：一只支持完整 ANC 的耳机，在系统里只能"连上"，不能调节。

Honor gates the ANC panel, the device-center service card and the battery pipeline behind
first-party earbuds: the panel is filtered out by device capability, the device center only
lists devices from `devicemanager.getDevices()`, and the stock `setNoiseCtrlMode` goes down
the MBB property channel that a third-party SPP bud does not understand (it speaks the
NiceHCK private `0x4E` SPP frames). Net result: a fully capable ANC earbud can only
*connect* — it cannot be controlled.

## 功能一览 / Features

| 功能 Feature | 说明 Description |
| --- | --- |
| 蓝牙设置页降噪面板 / In-settings ANC panel | 强制放开 `NoiseControlController` 显示门槛，三档：**实验性降噪 / 深度降噪 / 普通降噪**（status 5/4/3 → SPP `0x4E` 帧）/ Forces the panel gate open; three gears: experimental / deep / normal ANC |
| 设备中心接入 / Device-center entry | 第三方耳机出现在**设备中心球位**并可开服务卡（11 环注入：列表三出口、球创建、渲染参数、布局 mask、`c2.s.o` 注册表、在线态断根、注入 classloader 修正）/ The bud appears as a device-center ball with a full service card (11-stage injection) |
| 设备中心降噪按钮 / Device-center ANC buttons | `EarphoneNoiseData.fromBatteryData` 为 null 时注入三模式与当前档位，按钮点亮即用 / Injects supported modes when the stock data is null — buttons light up and work |
| 下发桥 / Dispatch bridge | `PropertyUtils.setNoiseCtrlMode` 拦截 → 映射为模块 status → 广播 → 蓝牙进程 SPP `0x4E` / Bridges Honor's dispatch into the SPP link |
| 控制中心磁贴 / QS tile | `QuickSettingsAncTile` 循环切换三档（5→4→3→5），点击即生效 / Cycles the three gears from the tile |
| 电量显示 / Battery | 蓝牙进程写 `Settings.Global[magicorig_battery]` 跨进程桥，设置页与 App 首页同时显示左右耳独立电量 / Left/right battery over a `Settings.Global` bridge |
| 通知电量追加 / Notification battery | 音频切换（SWS）通知自动追加 `· L% R%` / Appends battery to the audio-switch notification |
| 断联通知自清 / Auto-clear | 耳机断开后电量通知自动取消 / Notification cancelled on disconnect |
| 记住上次档位 / ANC memory | `Settings.Global[magicorig_last_anc]` 持久化；进页面只恢复 UI 选中态、**不下发**（`skipAncDispatch` 跳窗）/ Remembers the last gear without re-dispatching on page entry |
| 管理 App / Companion app | Material3 UI：首页（电量环、默认档位、模块自检）+ 关于页（仓库/引用/技术栈）/ Material3 app with home & about pages |

## 原理 / How it works

### 降噪下发链（蓝牙设置页 / 设备中心 / 磁贴，三入口汇一）

```
用户点击 [实验性降噪 / 深度降噪 / 普通降噪]
  ├─ 设置页: MultiStateSwitchingPanelPreference.onClick
  │        → NoiseControlPanelController.onMultiStateClicked(index, mode)
  ├─ 设备中心: EarphoneNoiseSectionController.onNoiseModeClick(NoiseMode)
  │        → PropertyUtils.setNoiseCtrlMode(deviceId, ncValue, cb)   ← hook 拦截
  └─ 磁贴:   QuickSettingsAncTile.onClick
        ↓ 统一映射 index/nc → status (5 实验性 / 4 深度 / 3 普通)
  广播 com.redwind.magicorig.ACTION_ANC_SELECT (status)     [settings/controlcenter 进程]
        → RfcommController.setANCMode(status)               [com.android.bluetooth 进程]
        → SPP 0x4E 帧 → 耳机切换 (~50ms)
        例: 实验性降噪 → 4E 05 00 00 01 02 10 00
```

### 设备中心接入（11 环注入，全部实测）

```
ControlCenterProvider.getDeviceList        → ext JSON 注入 LinkDevice（兜底）
DeviceBallManager 三列表出口               → 球列表含本设备（getAll/getDeviceInfoList/ShowRemote）
createDeviceBall                          → 渲染参数补齐(prodId/mac/index) + setIsOnline
BasicDeviceBallView.isOnline               → 断根恒 true（属性监听会重置）
DeviceBallManager.getDeviceInfoById        → deviceInfoMap 兜底（电量/状态查询断链修复）
DeviceLayoutConfig.selectLayout            → Third_EarPhone → REMOTE_EARPHONE（mask 含电量+降噪位）
c2.s.o(deviceId)                           → LinkDevice 注册表兜底（gen 早退根因）
genDeviceServiceInfo → createSections      → 三门通过 → createEarphoneNoiseSection → SEC
EarphoneNoiseData.fromBatteryData          → findClass 宿主 loader 注入三模式（CNFE 修复）
PropertyUtils.setNoiseCtrlMode             → 下发桥
onNoiseModeClick                           → 观察点
```

关键工程决策 / Key engineering decisions:

- **`findClass` 而非 `Class.forName`**：hook 回调里访问宿主类必须走宿主 ClassLoader —— `Class.forName` 默认用模块 loader，`EarphoneNoiseData$NoiseMode` 直接 CNFE，被 `ProtectiveHooker` 静默吞掉，表现为"日志成功但按钮永远灰"。
- **兜底在查询出口**：荣耀的数据（`deviceInfoMap`、`c2.s` 注册表、在线态）由 devicemanager 推送维护，第三方耳机没有推送 —— 在每个查询出口兜底注入，比逐处修补上游可靠。
- **位掩码是关键**：`HnDeviceProductType`、`ServiceSectionType`、`layoutMask` 全是位掩码；`Third_EarPhone & Third_EarPhone ≠ 0` 保证类型门天然通过。
- **广播桥跨进程**：`Settings.Global` + 显式广播把 settings/controlcenter 的点击送进 bluetooth 进程，复用已打通的 RFCOMM/SPP 通道，不新造协议。

## 环境要求 / Requirements

* 荣耀 MagicOS 设备（实测 AAK-AN00 / MagicOS 11 / Android 17）：KernelSU 或 Magisk + LSPosed（API 101+，Zygisk 模式）。
  A rooted Honor MagicOS device (tested: AAK-AN00 / MagicOS 11 / Android 17): KernelSU or Magisk + LSPosed (Zygisk, API 101+).
* 原道 OriG in 耳机（NickHCK，SPP 协议）。
  The NickHCK YuanDao OriG in earbuds (SPP protocol).
* 作用域 6 包（APK 内已声明推荐作用域）：`com.android.bluetooth`、`com.android.settings`、`com.android.systemui`、`com.hihonor.audioaccessorymanager`、`com.hihonor.imedia.sws`、`com.hihonor.controlcenter`。
  Scopes (declared in the APK): bluetooth, settings, systemui, audioaccessorymanager, imedia.sws and controlcenter.
* 仅在 MagicOS 上实测；其他系统类名可能变化，Hook 未命中时功能静默失效（不影响系统稳定）。
  Tested on MagicOS only; on other builds the hooks may silently miss (nothing breaks).

## 使用方法 / Usage

1. 安装 APK，在 LSPosed 中启用模块，作用域勾选上列 **6 个包**。
   **特别注意：`com.hihonor.controlcenter`（荣耀互联设备中心）必须在 LSPosed 管理器 UI 中手动勾选一次** —— 直接改配置数据库不生效，这是本模块踩过最深的坑。
   Install the APK, enable the module and select the 6 scopes above. **Important: the
   `com.hihonor.controlcenter` scope must be checked once in the LSPosed manager UI**
   (editing the config database alone does not take effect).
2. 强制停止被 hook 的应用或软重启（`su -c 'setprop ctl.restart zygote'`）。
   Force-stop the target apps or soft-reboot zygote.
3. 连接耳机，即可使用以下入口：
   Connect the earbud and use any entry below:

| 入口 Entry | 位置 Where |
| --- | --- |
| 降噪三档 / ANC gears | 蓝牙设置 → 耳机详情页 → 噪声控制 / Bluetooth settings → device detail |
| 设备中心降噪 / Device-center ANC | 控制中心 → 设备中心卡片条 → 点耳机球 → 噪声控制 / Control center → device-center bar → tap the bud ball |
| 快捷磁贴 / Quick tile | 控制中心编辑 → 添加 MagicOriG 磁贴 → 循环切换三档 / Control-center edit → add the tile |
| 管理 App / App | 桌面 MagicOriG 图标（电量、默认档位、自检）/ Home-screen app |

### 默认降噪档位 / Default ANC gear

App 首页可选 **实验性降噪 / 深度降噪 / 普通降噪**（默认：实验性），存入
`Settings.Global[magicorig_last_anc]`；同时作为设备中心与磁贴「降噪」一档的落点。

Pick the default gear in the app home (experimental / deep / normal, default experimental);
it is persisted in `Settings.Global` and is what the device-center / tile "ANC" key applies.

### 已知问题 / Known issues

* **在线态偶发回退**：荣耀属性监听（`DeviceProductManager.onPropertyInfoChange`）可能把第三方设备标回离线，卡片退化为"响铃/连接"样式。模块已在 `isOnline()` 读取口断根，若仍复现，重开设备中心即可刷新。
  The stock property listener may flip our device back offline (card degrades to ring/connect
  only); the module break-roots this at `isOnline()` — reopening the device center refreshes it.
* **球位坐标会变**：设备中心每次打开布局重排，点球前先肉眼定位。
  Ball positions are re-laid out on every open — locate the ball visually first.

## 从源码构建 / Build from source

Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.21（JDK 21）：

```
gradle.bat --no-daemon assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

Build: Gradle 8.9 + AGP 8.5.2 + Kotlin 2.0.21 (JDK 21), output under
`app/build/outputs/apk/release/`。若 `app/signing/magicorig.jks` 不存在会回退 debug 签名。

## 项目结构 / Project layout

```
├── app/src/main/resources/META-INF/xposed/
│   ├── java_init.list         # 现代 libxposed 入口 / modern entry (HookEntry)
│   ├── module.prop            # min/targetApiVersion（缺 targetApiVersion 会让 LSPosed 列表 NPE）
│   └── scope.list             # 推荐作用域 6 包 / recommended scopes
├── app/src/main/java/com/redwind/magicorig/hook/
│   ├── HookEntry.kt           # 按包分发 / per-package dispatch
│   ├── SettingsHeadsetHook.kt # 设置页降噪面板 + 档位记忆 + skipAncDispatch
│   ├── ControlCenterNoiseHook.kt  # 设备中心 11 环注入 / device-center injection
│   ├── RfcommController.kt    # SPP 0x4E 收发 + 电量/ANC 广播桥 (bluetooth 进程)
│   ├── QuickSettingsAncTile.kt    # 控制中心磁贴 / QS tile
│   └── MediaSwsBatteryHook.kt # 音频切换通知电量追加
├── app/src/main/java/com/redwind/magicorig/ui/
│   ├── App.kt                 # Material3 首页/关于 / M3 pages
│   └── Theme.kt               # M3 明暗配色 / light & dark schemes
├── docs/magicos11-integration.md   # 完整逆向文档 / full RE notes
└── tools/                     # 验证与截图辅助 / verification helpers
```

## 版本历史 / Changelog

* **v1.0.0**（2026-10-09）：**正式版** —— 三档降噪（实验性/深度/普通）、Material3 管理 App、控制中心磁贴、设备中心完整接入（11 环注入）、断联通知自清、通知电量追加、档位记忆。
  First stable: three ANC gears, Material3 app, QS tile, full device-center entry, auto-clear
  notification, notification battery, ANC memory.
* **v1.0.0-alpha**（2026-10-08）：首个公开预发布 —— 设置页降噪面板全链打通（逆向 MagicOS 噪声控制链）、电量跨进程桥、SWS 通知电量。
  First public prerelease: the in-settings ANC panel end-to-end, the battery bridge, SWS
  notification battery.

## 免责声明 / Disclaimer

* 本项目仅供学习与研究 Android Hook / 逆向技术使用，请勿用于商业用途。
  For learning and research on Android hooking and reverse engineering only.
* **与荣耀公司 / HONOR 无任何关联**；相关商标与系统界面版权归原厂所有。**不支持华为 HarmonyOS。**
  Not affiliated with Honor Device Co., Ltd. **Huawei HarmonyOS is not supported.**
* 本模块为 AI 生成（见文首声明），使用本模块产生的任何后果由使用者自行承担。
  AI-generated (see the header) — use at your own risk.

## License

MIT
