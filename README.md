# MagicOriG

为 **NickHCK YuanDao OriG in** 耳机接入 **MagicOS / HarmonyOS** 的 LSPosed 模块。

基于 [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) 移植，适配华为 MagicOS 系统架构。

## 系统要求

- **LSPosed**：需 Zygisk 模式
- **Android 版本**：Android 14+（MagicOS 8 / HarmonyOS NEXT）
- **Root 权限**：需要（用于作用域管理）
- **LSPosed API**：101+

## 引用项目

- [HyperOriG](https://github.com/KiriChen-Wind/HyperOriG) — 原始 HyperOS 适配版（核心 RFCOMM/电量/ANC 逻辑）
- [LibXposed API](https://github.com/libxposed/api) — 现代 LSPosed 接口

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

## MagicOS 适配（未完成）

现有 hook 目标全部来自 HyperOriG（小米 HyperOS），在 MagicOS 上会**静默失败**（全部被 `runCatching` 吞掉）：

| hook 目标 | 归属 |
|-----------|------|
| `com.android.settings.bluetooth.HeadsetIDConstants` | MIUI |
| `com.android.bluetooth.ble.app.headset.BluetoothHeadsetService` | MIUI |
| `com.android.bluetooth.ble.app.MiuiBluetoothNotification` | MIUI |
| `com.android.bluetooth.ble.app.IMiuiHeadsetCallback` | MIUI |

要接上荣耀的降噪面板，必须先拿到 MagicOS 上真实的类名：

```powershell
powershell -ExecutionPolicy Bypass -File tools\discover-magicos-headset.ps1
powershell -ExecutionPolicy Bypass -File tools\discover-magicos-headset.ps1 -Package com.android.settings
```

脚本会把系统 Settings/蓝牙 APK 拉下来、解出 `classes*.dex` 并扫描耳机/降噪相关关键字，
命中结果替换掉 `HookEntry.kt` / `BluetoothUpstreamHeadsetHook.kt` 里的 MIUI 目标。

## 调试

```bash
adb logcat -s "MagicOriG-*"
```

## License

MIT
