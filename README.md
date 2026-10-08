# MagicOriG

让 **原道 OriG in（NickHCK）** 第三方耳机在 **荣耀 MagicOS** 上获得与荣耀自家耳机一致的降噪体验。

> **本模块仅面向荣耀 MagicOS，不支持华为 HarmonyOS / HarmonyOS NEXT。**

---

## 功能

| 功能 | 说明 |
|------|------|
| 🎧 **降噪模式切换** | 在蓝牙设置详情页直接调节降噪：实验性降噪 / 透传 / 关闭 |
| 🔋 **耳机电量显示** | 蓝牙设置页、App 首页同时显示左右耳独立电量 |
| ⚡ **控制中心快捷磁贴** | 下拉控制中心一键切换降噪模式（三档循环） |
| 💾 **记住档位** | 自动记住上次使用的降噪模式，下次进入自动恢复选中态 |
| 🔔 **音频通知电量** | 音频切换通知自动追加左右耳电量 |
| 🚫 **断联通知自清** | 耳机断开后电量通知自动消失 |
| 📱 **新 UI 管理 App** | 液态玻璃风格设置页：首页电量/档位 + 关于页仓库链接 |
| 🔧 **模块自检** | App 内置 LSPosed 识别检测卡片 |

## 实测数据

### 降噪 SPP 帧（真机 logcat 截取）

| UI 按钮 | Status | AncMode | SPP 帧（16 进制） |
|---------|--------|---------|-------------------|
| **降噪** | 5 | EXPERIMENT `0x10` | `4E 05 00 00 01 02 10 00` |
| 透传 | 2 | TRANSPARENT `0x01` | `4E 05 00 00 01 02 01 00` |
| 关闭 | 1 | OFF `0x00` | `4E 05 00 00 01 02 00 00` |

### 性能数据

| 指标 | 值 |
|------|-----|
| 降噪面板打开 → UI 渲染 | ~1.5s |
| 点击按钮 → SPP 帧发出 | ~50ms |
| 电量查询周期 | 30s |
| 跨进程广播延迟 | &lt;10ms |

### 端到端链路

```
用户点击 [降噪/透传/关闭]
  → MultiStateSwitchingPanelPreference.onClick(View)
  → NoiseControlPanelController.onMultiStateClicked(index, mode)
  → 广播 ACTION_ANC_SELECT(status)       [Settings 进程]
  → RfcommController.setANCMode(mode)    [bluetooth 进程]
  → SPP 0x4E 帧 → 耳机切换降噪
```

## 使用要求

### 硬件

| 项目 | 要求 |
|------|------|
| 耳机 | 原道 OriG in（NickHCK）— **必须** |
| 手机 | 荣耀 MagicOS 设备（测试机：AAK-AN00, MagicOS 11, Android 17） |

### 软件

| 项目 | 要求 |
|------|------|
| 系统 | 荣耀 MagicOS（Android 14+） |
| Root | **需要**（KernelSU / Magisk 均可） |
| LSPosed | Zygisk 模式，API 101+ |
| 蓝牙 | 支持 A2DP + SPP RFCOMM |

### 作用域

4 个进程必须在 LSPosed 中勾选：

| 包名 | 用途 |
|------|------|
| `com.android.bluetooth` | RFCOMM 连接、电量/ANC 数据收发 |
| `com.android.settings` | 蓝牙设置页降噪面板、电量显示 |
| `com.hihonor.audioaccessorymanager` | 荣耀耳机面板进程 |
| `com.hihonor.imedia.sws` | 音频切换通知电量追加 |
| `com.android.systemui` | 状态栏耳机图标 |

> **注意**：修改作用域后需**在 LSPosed 管理器中重新关闭再打开模块**以刷新。

## 安装

### 方法一：下载 Release（推荐）

1. 从 [Releases](https://github.com/yingjfd/MagicOriG/releases) 下载最新 APK
2. 安装 APK
3. 打开 LSPosed 管理器 → 模块 → 勾选 MagicOriG → 设置作用域 → 重启设备

### 方法二：自行构建

```bash
# 克隆仓库
git clone https://github.com/yingjfd/MagicOriG.git
cd MagicOriG

# 构建发布版
./gradlew assembleRelease

# 产物
ls app/build/outputs/apk/release/app-release.apk
```

**JDK 21 / AGP 8.5.2 / Kotlin 2.0.21**

## 卸载

1. LSPosed 管理器 → 关闭 MagicOriG
2. 卸载 APK
3. 可选：`settings delete global magicorig_battery`、`settings delete global magicorig_last_anc`

## 问题排查

### LSPosed 看不到模块

检查 `META-INF/xposed/module.prop` 是否包含 `targetApiVersion`（缺失会导致 NPE 中断整个模块列表）。

```pro
minApiVersion=101
targetApiVersion=102
```

### 降噪面板不显示

- 确保耳机已连接 A2DP
- 重启 Settings 进程：`adb shell am force-stop com.android.settings`

### 控制中心磁贴不出现

下拉控制中心 → 点编辑（铅笔图标）→ 在底部找到 **MagicOriG** → 拖入

### ADB 日志

```bash
adb logcat -s "MagicOriG-*"
# 模块内部日志
adb shell "su -c 'grep -a MagicOriG /data/adb/lspd/log/modules_*.log | tail -20'"
```

## 技术栈

| 域 | 技术 |
|----|------|
| 模块框架 | LSPosed / LibXposed API 102 |
| Hook 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material3 |
| 通信 | SPP RFCOMM（NiceHCK 蓝牙协议）、MBB 协议桥 |
| 跨进程 | `Settings.Global` + 系统广播 |
| 构建 | Gradle 8.9 / AGP 8.5.2 |

## 引用项目

- [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) — 原始 RFCOMM/电量/ANC 逻辑
- [LibXposed API](https://github.com/libxposed/api) — LSPosed 接口
- [miuix](https://compose-miuix-ui.github.io/miuix/zh_CN/) — UI 风格参考
- [Liquid Glass](https://liquidglass.qmdeve.com/zh/) — 底栏玻璃效果灵感

## License

MIT License — 详情见 [LICENSE](LICENSE)

---

**Made with ❤️ for the MagicOS community**
