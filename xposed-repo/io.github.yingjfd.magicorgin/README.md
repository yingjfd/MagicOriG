# MagicOriG

让第三方 SPP 耳机 **NickHCK 原道 OriG in** 在荣耀 MagicOS 上像自家耳机一样使用——设备中心直接调节降噪、查看双耳电量，控制中心快捷磁贴一键切换。

> ⚠️ 本项目由 AI 在人类指导下生成并维护，包括全部逆向分析与 Hook 代码。使用请自担风险。

## 功能

| 功能 | 说明 |
|------|------|
| 🎧 设备中心降噪 | 下拉控制中心 → 拖出「设备中心」到主栏 → 点耳机球 → 降噪 / 通透 / 关闭三钮，点击即下发 SPP 帧（~50ms） |
| 🔋 双耳电量 | 设备中心卡片显示 L / R 独立电量条 |
| 🔄 控制中心磁贴 | 添加 MagicOriG 磁贴，循环切换降噪三档 |
| 🔔 通知电量 | 音频切换通知显示「原道 OriG in / 左耳 x% 右耳 x%」 |
| 🧹 断联自清 | 耳机断开后通知自动取消 |

## 下载

[MagicOriG-v1.1.8.apk](https://github.com/yingjfd/MagicOriG/releases/download/v1.1.8/MagicOriG-v1.1.8.apk)

所有版本见 [Releases](https://github.com/yingjfd/MagicOriG/releases)。

## 作用域

| 包名 | 用途 |
|------|------|
| `com.android.bluetooth` | SPP 协议下发、状态查询 |
| `com.android.systemui` | 通知文案改写、磁贴 |
| `com.hihonor.audioaccessorymanager` | 荣耀耳机服务兼容 |
| `com.hihonor.imedia.sws` | 音频切换通知电量 |
| `com.hihonor.controlcenter` | **设备中心**：球列表、服务卡、降噪按钮、双耳电量 ⚠️ 此项必须在 LSPosed 管理器 UI 中手动勾选（仅修改配置文件不生效） |

## 要求

- 荣耀 MagicOS 9.0+（Android 14+）
- KernelSU / Magisk + LSPosed（Zygisk 模式，API 101+）
- NickHCK 原道 OriG in 耳机

## 使用方法

1. 安装 APK，在 LSPosed 中启用模块，勾选上表作用域
2. `su -c 'setprop ctl.restart zygote'` 软重启（或重启手机）
3. 连接耳机

## 降噪三档说明

| 按钮 | 协议 | SPP 帧 |
|------|------|--------|
| 降噪 | 实验性降噪（默认档位） | `4E 05 00 00 01 02 10 00` |
| 通透 | ANC_TRANSPARENT | `4E 05 00 00 01 02 01 00` |
| 关闭 | ANC_OFF | `4E 05 00 00 01 02 00 00` |

## 项目主页

完整源码、构建说明与降噪链详解见 [GitHub](https://github.com/yingjfd/MagicOriG)。
