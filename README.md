# MagicOriG

为 **NickHCK YuanDao OriG in** 耳机接入 **荣耀 MagicOS** 的 LSPosed 模块。

基于 [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) 移植，适配荣耀 MagicOS 系统架构。

> ## ⚠️ 支持范围声明
>
> **本项目仅面向荣耀（HONOR）的 MagicOS，不为华为（HUAWEI）系统提供任何修改。**
>
> - 不适配华为 HarmonyOS / HarmonyOS NEXT，也不针对华为设备做任何改动
> - 不包含、不依赖、不兼容 HMS / 华为自有蓝牙组件
> - 文档、脚本与 hook 目标均以荣耀 MagicOS 为唯一目标；在华为系统上**不提供支持，也未做过验证**
>
> 出现于历史文档中的 "HarmonyOS" 字样均为早期描述遗留，不代表本项目支持华为系统。

## 系统要求

- **系统**：荣耀 **MagicOS**（Android 14+）
- **LSPosed**：需 Zygisk 模式
- **Root 权限**：需要（用于作用域管理）
- **LSPosed API**：101+

## 引用项目

- [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) — 原始 HyperOS 适配版（核心 RFCOMM/电量/ANC 逻辑）
- [LibXposed API](https://github.com/libxposed/api) — 现代 LSPosed 接口
- [miuix](https://compose-miuix-ui.github.io/miuix/zh_CN/) — UI 风格参考

## 功能

- RFCOMM SPP 连接读取 OriG in 耳机电量/ANC 状态
- Hook `com.android.bluetooth` 强制识别 OriG in 为 TWS 耳机
- 状态栏无线耳机图标显示
- 电量/降噪模式广播到系统蓝牙设置
- 模块设置页面

## 与 HyperOriG 的差异

| 项目 | HyperOriG (HyperOS) | MagicOriG (MagicOS) |
|------|---------------------|---------------------|
| 蓝牙包 | `com.xiaomi.bluetooth` | `com.android.bluetooth` |
| SystemUI | `com.android.systemui` | `com.android.systemui` |
| MiLink Hook | `com.milink.service` | 移除（MagicOS 无 MiLink） |
| 强提示 | MiuiStrongToast（视频+文字） | 标准 Notification |
| 灵动岛 | FocusIsland API | 不支持 |

## 构建

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 打包完成后会自动执行 verifyXposedModule 校验任务
```

发布版：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 若 app/signing/magicorig.jks 不存在（该文件在 .gitignore 中），会自动回退到 debug 签名
```

## 为什么 LSPosed 会「看不到」这个模块

LSPosed 判断一个 APK 是不是模块，只看两件事（见 LSPosed 源码
`app/src/main/java/org/lsposed/manager/util/ModuleUtil.java`）：

1. **现代**：APK 内存在 `META-INF/xposed/java_init.list`
2. **旧式**：manifest 元数据里有 `xposedminversion`

两者满足其一即进入模块列表。本模块两者都声明了，但仍出现过「完全看不到」的情况，
根因不在作用域，而在 `module.prop`：

```java
// ModuleUtil.extractIntPart —— 原实现对 null 直接 NPE
public static int extractIntPart(String str) {
    int result = 0, length = str.length();
```
```java
// InstalledModule 构造函数
minVersion    = extractIntPart(prop.getProperty("minApiVersion"));
targetVersion = extractIntPart(prop.getProperty("targetApiVersion"));  // 缺键 -> NPE
```
```java
} catch (IOException | OutOfMemoryError e) { ... }   // 只捕获这两类，NPE 逃逸
```

`targetApiVersion` 缺失时 `InstalledModule` 构造抛 NPE，而外层
`reloadInstalledModules()` 的循环**没有任何 try/catch**，异常直接中断整个模块列表加载 —— 表现就是
「模块列表里一个模块都看不到」。

因此 `META-INF/xposed/module.prop` 必须显式写上 `minApiVersion` **和** `targetApiVersion`。

> `staticScope` 与「能不能被识别」无关：`ScopeAdapter` 根本不读它；
> `scope.list` 无论 `staticScope` 取值如何都会作为推荐作用域，并在启用模块时自动写入。

## 自检

构建产物（无需真机）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\verify-lsposed-module.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
```

真机上（打开本 App 即可看到「模块识别自检」卡片），或从设备拉取已安装 APK 后用上面的脚本检查。

## 使用方法

1. 安装 LSPosed（Zygisk 模式）
2. 安装本模块 APK 并在 LSPosed 中启用
3. 作用域来自 `META-INF/xposed/scope.list`：`com.android.bluetooth`、`com.android.systemui`、`com.android.settings`
4. 重启设备
5. 配对 OriG in 耳机（识别名含 YUANDAO / OriG / NiceHCK）
6. 打开本模块设置页面调整配置

## 降噪面板接入（已完成）

第三方耳机（原道 OriG in）现在可以像荣耀自家耳机一样，在蓝牙设置详情页里调节降噪。

```
设置详情页 [噪声控制: 降噪 / 透传 / 关闭]
  → NoiseControlController.displayPreference        （x7.a.r() 强制可用后才 add）
  → MultiStateSwitchingPanelPreference.onClick
  → NoiseControlPanelController.onMultiStateClicked(index, mode)
  → 广播 com.redwind.magicorig.ACTION_ANC_SELECT (status)
  → RfcommController.setANCMode()   [com.android.bluetooth 进程]
  → SPP 0x4E 帧 → 耳机切换
```

| UI 按钮 | status | `AncMode` | SPP 帧字节 |
|---------|--------|-----------|-----------|
| 降噪 | 5 | `EXPERIMENT` | `4E 05 00 00 01 02 10 00` |
| 透传 | 2 | `TRANSPARENT` | `4E 05 00 00 01 02 01 00` |
| 关闭 | 1 | `OFF` | `4E 05 00 00 01 02 00 00` |

完整逆向过程与排障清单见 [docs/magicos11-integration.md](docs/magicos11-integration.md)。

### 已被替换的 MIUI/HyperOS 目标

原始 hook 目标全部来自 HyperOriG（小米 HyperOS），在 MagicOS 上会**静默失败**（被 `runCatching` 吞掉）：

| hook 目标 | 归属 |
|-----------|------|
| `com.android.settings.bluetooth.HeadsetIDConstants` | MIUI |
| `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService` | MIUI |
| `com.android.bluetooth.ble.app.MiuiBluetoothNotification` | MIUI |
| `com.android.bluetooth.ble.app.IMiuiHeadsetCallback` | MIUI |

定位 MagicOS 真实类名的探测脚本（可重跑）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\discover-magicos-headset.ps1
powershell -ExecutionPolicy Bypass -File tools\discover-magicos-headset.ps1 -Package com.android.settings
```

## 调试

```bash
adb logcat -s "MagicOriG-*"
```

## License

MIT
