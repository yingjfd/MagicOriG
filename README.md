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
| 设备中心降噪 / Device-center ANC | 下拉控制中心 → 点编辑 → **拖出「设备中心」到主栏** → 点耳机球 → 噪声控制 / Pull down → edit → drag **Device Center** to the main bar → tap the bud ball |
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

* **v1.0.9**（2026-10-11）：蓝牙页真实档位显示的最后一环打通 ——
  ①状态广播的发送在拿不到上下文时会静默跳过（查询结果送不到设置页）→ 加兜底；
  ②反汇编+运行时实测发现面板按钮的内部编号（降噪=1、透传=2、关闭=0）与耳机协议字节
  完全不同，此前按协议字节高亮导致选中错位/不高亮 —— 已按面板真实编号映射。
  实测：进页面后查询回包 → 面板高亮与耳机本体当前模式一致。
* **v1.0.8**（2026-10-11）：**修复两个用户实测的 bug** ——
  ①蓝牙界面三档位的模式映射写错：点「透传」实发深度降噪、点「关闭」实发普通降噪
  （此前误当成了实验性/深度/普通三档），现在正确对应 降噪=实验性、透传、关闭；
  ②进入设备中心/蓝牙界面时改为**查询耳机当前模式并显示**（只读查询帧，不再下发调模式包），
  卡片和蓝牙页显示的就是耳机本体的真实档位；同时修复真实档位记录拿不到上下文时的
  静默丢失、心跳重复写入、档位记忆被通透/关闭污染等问题。
  Fixed the ANC gear→mode mapping (pass-through/close used to send the wrong modes) and
  both UIs now show the earbud's actual mode via a read-only query (no mode-set packets).
* **v1.0.6**（2026-10-10）：内部验证工具 —— 一键回归脚本（部署、开设备中心、点球、抓日志、
  截图一条龙），之后每轮改动都能快速跑完全量检查。无功能变化。
  Internal verification script (one-shot regression: deploy → device center → ball → logs).
  No functional changes.
* **v1.0.5**（2026-10-10）：电量显示补上最后一道保险 —— 确认了荣耀的显示条件是
  `batteryLevel > 0`，并给卡片写电量的入口加了兜底（任何环节写入 0 或 -1 都会被
  真实电量替换），三层防护确保耳机电量稳定显示。
  Battery display triple-insurance: any 0/-1 write is replaced by the real value.
* **v1.0.4**（2026-10-10）：**设备中心现在显示耳机的真实降噪档位**。反汇编证实设备中心
  不存在"自动下发"路径（所有下发只来自按钮点击）—— 之前卡片固定显示"降噪"选中是默认值，
  会被误认为打开界面就把耳机切了。现在蓝牙进程在每次成功切换后记录真实档位，
  卡片按真实状态显示（真实记录 → 用户默认档位 → 降噪 三级回退）。
  Card now shows the earbud's real ANC mode (recorded by the bluetooth process);
  no auto-dispatch path exists in the device center (verified by decompilation).
* **v1.0.3**（2026-10-10）：设备中心卡片电量改为在数据生成时直接写入（上游打桩，不再依赖
  荣耀的多层显示条件）；补充卡片诊断观察；文档固化第二阶段全部修复记录。
  Device-center card battery now written directly at data-generation time; diagnostics
  added; full fix history documented.
* **v1.0.2**（2026-10-10）：**电量通知真正上屏**。通知文案改到系统渲染层（SystemUI）执行 ——
  此前在音频 app 进程内的改写会被荣耀自有渲染逻辑覆盖（日志成功但通知栏永远显示原文）；
  现在通知栏实拍显示「原道 OriG in　左耳 100% 右耳 100%」。同时修复构建脚本的中文编码问题。
  Battery notice now actually renders (rewrite moved to the SystemUI render layer);
  verified on the notification bar.
* **v1.0.1**（2026-10-09）：**bug 修复集** —— ①设备中心点击切换无效（广播 status 曾为 String，
  bluetooth 端 `getIntExtra` 读到 0 不发帧）与点一次后全部按钮卡死（荣耀 MBB 对第三方设备不回调
  → `isSetting` 永真，改为模拟 `onResult` 回调）；②进入蓝牙/设备中心页面自动下发降噪
  （档位恢复 `Q` 落在 skip 窗口 0.5s 间隙后 → Q 包入新 skip 窗口）；③耳机断连后球仍显示
  （新增 `magicorig_connected` 门控，信号挂 A2DP 状态回调）；④双耳电量通知迁移到
  「音频切换」app 连接耳机通知（`RemoteViews.setTextViewText` 改写为
  「原道 OriG in / 左耳 x% 右耳 x%」），蓝牙进程不再单独弹电量通知。
  Bug-fix batch: device-center tap dispatch + one-click lockout, no more auto-ANC on
  page entry, ball disappears on disconnect (connected gating on A2DP callbacks),
  battery notification moved to the audio-switch notice.
* **v1.0.0**（2026-10-09）：**正式版** —— 三档降噪（实验性/深度/普通）、Material3 管理 App、控制中心磁贴、设备中心完整接入（11 环注入）、断联通知自清、通知电量追加、档位记忆。
  First stable: three ANC gears, Material3 app, QS tile, full device-center entry, auto-clear
  notification, notification battery, ANC memory.
* **v1.0.0-alpha**（2026-10-08）：首个公开预发布 —— 设置页降噪面板全链打通（逆向 MagicOS 噪声控制链）、电量跨进程桥、SWS 通知电量。
  First public prerelease: the in-settings ANC panel end-to-end, the battery bridge, SWS
  notification battery.

## 免责声明 / Disclaimer

* 本项目仅供学习与研究 Android Hook / 逆向技术使用，请勿用于商业用途。
  For learning and research on Android hooking and reverse engineering only.
* **与荣耀公司 / HONOR 无任何关联**；相关商标与系统界面版权归原厂所有。
  Not affiliated with Honor Device Co., Ltd.
* 本模块为 AI 生成（见文首声明），使用本模块产生的任何后果由使用者自行承担。
  AI-generated (see the header) — use at your own risk.

## License

MIT
