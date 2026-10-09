# MagicOS 11 耳机降噪接入 —— 真机逆向结论

设备：**HONOR AAK-AN00 / MagicOS 11.0.0 / Android 17 (API 37)**
`ro.build.display.id = AAK-AN00 11.0.0.109(SP9C00E109R202P2)`

以下全部来自真机 `pm path` + `dexdump` 实测，非推测。

---

## 1. 作用域：包名是 AOSP 的，不用改

| 目标 | APK 路径 |
|------|----------|
| `com.android.settings` | `/system/priv-app/Settings/Settings.apk` |
| `com.android.bluetooth` | `/apex/com.android.bt/app/Bluetooth@CP2A.260605.016/Bluetooth.apk` |
| `com.android.systemui` | `/system/priv-app/SystemUI/SystemUI.apk` |
| `com.hihonor.audioaccessorymanager` | `/system/app/AudioAccessoryManager/AudioAccessoryManager.apk` |

荣耀核心蓝牙/设置确实沿用 AOSP 包名，现有 `scope.list` 三项是对的。

> ⚠️ `com.hihonor.bluetooth` 存在但只是**联系人导入工具**，与蓝牙栈无关，不要 hook。

---

## 2. 荣耀耳机面板的位置（新增目标）

耳机功能 UI 全部在 **`com.hihonor.audioaccessorymanager`** 进程内：

```
com.hihonor.earphone.homepage.EarphoneDetailsActivity        耳机详情页
com.hihonor.earphone.homepage.EarphoneDetailsViewModel
com.hihonor.earphone.homepage.noisecontrol.NoiseControlViewModel   降噪控制
com.hihonor.earphone.homepage.BaseViewModel
com.hihonor.earphone.functioncontrol.FunctionControlActivity/ViewModel
com.hihonor.earphone.gesturecontrol.GestureControlActivity/ViewModel
com.hihonor.earphone.find.ui.FindDeviceActivity              查找耳机
com.hihonor.earphone.service.EarphoneService
com.hihonor.productfeature.basefeature.noisecontrol.modeswitch.BaseModeSwitchActivity
                                                              ↑ 字符串 "NoiseCancelingState" 就在这里
com.hihonor.earphonesdk.uiconfig.entity.UiConfigLite$NoiseControl
com.hihonor.earphonesdk.uiconfig.entity.honorconfigdata.DeviceProperty
com.hihonor.productconnect.bluetoothlib.MbbApi              蓝牙能力层
```

**降噪指令入口**（`MbbApi`，实测方法名）：

```
getNoiseControlFunction / setNoiseControlFunction
getNoiseControlLoopList / setNoiseControlLoopList
getNoiseControlLoopFocus / setNoiseControlLoopFocus
setNoiseControlLevel / setNoiseControlLoop
setNoiseControlState, noiseControlState=...
```

---

## 3. 「是否受支持」的开关（可直接写，已验证）

`com.hihonor.earphonesdk.uitl.SupportedDeviceSettings`：

```java
// b(ContentResolver): Set<String>
String s = Settings.Global.getString(cr, "supported_earphone_device");  // JSON 数组
if (TextUtils.isEmpty(s)) return new HashSet<>();
return gson.fromJson(s, <Set<String>>);

// d(ContentResolver, String): saveSupportedDevice
String addr = address.toUpperCase(Locale.ROOT);        // MAC 转大写
Set set = b(cr);
set.add(addr);
Settings.Global.putString(cr, "supported_earphone_device", gson.toJson(set));
```

即 **`Settings.Global["supported_earphone_device"]` = JSON 数组，元素是大写 MAC 地址**。

调用方：`EarphoneService`、`BatteryAppWidgetProvider`、`earphonesdk.uitl.j`。

### 真机验证（已执行并还原）

```bash
$ adb shell settings put global supported_earphone_device '["AA:BB:CC:DD:EE:FF"]'
$ adb shell settings get global supported_earphone_device
[AA:BB:CC:DD:EE:FF]        # 写入成功
$ adb shell settings delete global supported_earphone_device
$ adb shell settings get global supported_earphone_device
null                        # 已还原
```

**shell 可写** → 模块在 `com.android.bluetooth`（系统进程，持 `WRITE_SECURE_SETTINGS`）内同样可写。
因此最省事的接入方式是：耳机连接时把它的 MAC 追加进这个 JSON 数组。

---

## 4. 参照设备与待办

已配对设备里有 **`HONOR Earbuds X6`**（`xx:xx:xx:xx:c5:7d`）——荣耀自家耳机，
可在它连接时抓取 `supported_earphone_device` 的**真实取值格式**做对照。

当前 `supported_earphone_device = null`（未连接时为空）。

**目标耳机 `YUANDAO / OriG in / NiceHCK` 尚未配对**，需要先配对拿到 MAC。

已配对设备（`dumpsys bluetooth_manager`）：
`DARKRIM SHADOW`、`HONOR Earbuds X6`、`Xbox Wireless Controller`×2、
`Flydigi Direwolf 4`、`YINGJFDS-LEGION`

---

## 5. 接入方案

1. **登记**：耳机连接时调用 `Settings.Global.putString(cr, "supported_earphone_device", ...)` 把 MAC 加入数组
   （或 hook `SupportedDeviceSettings.b()` 让返回值恒含该 MAC）→ 荣耀把第三方耳机当受支持设备。
2. **降噪**：hook `NoiseControlViewModel` / `BaseModeSwitchActivity` 的读写，
   读 → 返回 `RfcommController` 的 `currentAnc`；
   写 → 转发到 `RfcommController.setANCMode()`（走 SPP，不走荣耀 `MbbApi` 私有通道）。
3. 蓝牙/设置两个 AOSP 包的作用域维持不变。

### 仍然未知（需要耳机连接后确认）

- `NoiseControlViewModel` 的具体读写方法签名
- 荣耀降噪模式取值枚举（我们已有 NiceHCK 侧的 `0x00~0x11`，缺两者映射）
- `MbbApi` 最终落到哪个蓝牙接口（若要复用它需继续逆向；走 SPP 则可绕过）

---

## 6. 补充：机型能力配置在 APK assets 里（决定"有没有降噪栏"）

`AudioAccessoryManager.apk` 的 `assets/` 下按 **4 位机型码** 存能力配置。

### 两张设备表

**`assets/baseSupportDeviceList.json`**（荣耀/华为自家）：

```json
{ "modelId": "00Y3DR", "productName": "HONOR Earbuds X6",
  "productId": "Y3DR", "supportJsonName": "Y3DR" }
```

注意最后一条 **`{ "modelId": "00VVD9", "productName": "Third", "productId": "Third" }` —— 第三方耳机专用**。

**`assets/earphone_support_list_builtin.json`**（含第三方 AirPods 与华为 FreeBuds）：

| modelId | 产品 | modelId | 产品 |
|---|---|---|---|
| `00FCPG` | **AirPods 4 降噪版** | `00G64H` | AirPods 4 非降噪版 |
| `00M0VQ` | AirPods Pro 2 | `00EFYB` | AirPods Pro 2 USB-C |
| `00S91A` | AirPods Pro 1 | `00FDAQ` | AirPods Pro 3 |
| `00SWEH` | FreeBuds 6 | `00GS26` | FreeBuds Pro 4 |

### 决定降噪栏的字段（已对比验证）

`assets/uiconfig/<CODE>.json` → `UiConfigLite`：

```jsonc
// FCPG.json（AirPods 4 降噪版）—— 有降噪
"homepage": {
  "noiseControl": { "noiseSwitchNum": 4, "wearNoiseSwitchPolicy": 1 },
  ...
}
```
```jsonc
// G64H.json（AirPods 4 非降噪版）—— 没有 noiseControl 键
"homepage": { "moreSetting": { ... }, ... }
```

即 **`homepage.noiseControl` 存在与否 = 面板显不显示降噪**，`noiseSwitchNum` = 档位数。
`uiconfig/` 目录下只有 AirPods 系列（EFYB/FCPG/FDAQ/G64H/M0VQ/MRCA/OSFI/PRUH/S91A/WEQW/X6MG）。

> `HONOR Earbuds X6` 无降噪 → 它的 `Y3DR.json` 是菜单表（menutype/menuinfo 结构），
> 且无 `uiconfig/Y3DR.json`，所以拿它做降噪参照没有意义，只能参照**登记格式**。

### 配置解析入口

- `com.hihonor.commonutils.bean.SupportEarphoneListOuc$EarphoneBaseInfo`
  字段 `supportJsonName` / `productId`，getter `getProductId()` `getSupportJsonName()`
- `com.hihonor.commonutils.o0` —— 读 `baseSupportDeviceList.json` 并按 `productId` 解析
- `com.hihonor.productfeature.basefeature.magichome.MagicHomeManager` 里有常量 `"Third"` / `"THIRD_AUDIO_DEVICE"`

---

## 7. 最终接入方案（据以上证据）

1. **登记**：耳机连接时把 MAC 追加进 `Settings.Global["supported_earphone_device"]`（格式：JSON 数组、大写 MAC）→ 面板出现，按 `Third` 身份展示。
2. **拿到降噪栏**（二选一）：
   - **A（推荐）** hook `SupportEarphoneListOuc$EarphoneBaseInfo` 的 `getProductId()`/`getSupportJsonName()`，让第三方耳机返回 `FCPG` → 加载 `uiconfig/FCPG.json` → 出现 4 档降噪栏；
   - **B** hook `UiConfigLite` 解析，给 `homepage` 注入 `noiseControl: {noiseSwitchNum: 4}`。
3. **桥接**：hook `NoiseControlViewModel` / `BaseModeSwitchActivity` 的降噪读写，
   读 → 返回 `RfcommController.currentAnc`；写 → 转发 `RfcommController.setANCMode()`（走 SPP，绕开荣耀 `MbbApi` 私有通道）。
4. 蓝牙/设置两个 AOSP 包作用域维持不变；新增作用域 `com.hihonor.audioaccessorymanager`。

### 待真机确认

- [ ] 连接 OriG in 后 `supported_earphone_device` 的真实取值（参照 HONOR Earbuds X6 连接时抓）
- [ ] 第三方耳机被判定为 `Third` 还是按名字匹配 modelId（需连上目标耳机观察 `o0` 的行为）
- [ ] `NoiseControlViewModel` 的读写方法签名
- [ ] 荣耀降噪档位值 ↔ NiceHCK `0x00~0x11` 的映射

---

## 8. LSPosed 在 MagicOS 上的坑

MagicOS 10 上 Honor 改过的 `PackageParser`（`HnPackageUtils.notNeedCustomedPermission()`
→ `ActivityManager.isSystemReady()`）会让 lspd 守护进程 NPE，导致
**system_server 作用域的模块被静默跳过**。本模块只用 app 作用域（bluetooth/systemui/settings），不受影响。
若日后需要 hook system_server，参考 `mengwuzhuanshou/HonorLsposedFix`。

---

## 9. 实测修正与现状（2026-10-06）

### ❌ 推翻：`supported_earphone_device` 不是荣耀自家耳机的登记机制

第 3 节把它当成了"开关"，**这是过度推断**。真机证据：

- HONOR Earbuds X6 **ACL 已连接**（`dumpsys bluetooth_manager` 显示 `ACL BR/EDR:Y`）
- 此时 `settings get global supported_earphone_device` **仍为 `null`**

即荣耀自家耳机连接时并不写这个键。它是可读写的，但用途另在别处
（可能与 `earphone_support_list_builtin.json` 里的第三方 AirPods 识别有关），
**不能作为接入的主路径**。第 7 节方案第 1 步需要重新设计。

### 对照实验：X6 vs 原道 OriG in（蓝牙设置列表，16:11 实拍）

| | HONOR Earbuds X6 | 原道 OriG in |
|---|---|---|
| 图标 | 荣耀自家耳塞专用图标 | 通用耳机图标 |
| 编解码 | AAC + mSBC | **LHDC** + mSBC |
| 电量 | **左 100%，右 100%** | 100%（单一值） |
| 降噪入口 | **无** | **无** |

两者在蓝牙列表里都只是标准条目，**都没有降噪卡片**。

### 关键结论

1. **荣耀的降噪面板不在蓝牙设置里** —— 蓝牙详情页是 AOSP 的
   `Settings$DeviceProfilesSettingsActivity`（重命名/设备类型/音频开关），
   点 ⓘ 和点名称都进不去荣耀面板；`EarphoneDetailsActivity` 还需
   `ACCESS_BIND_AUDIOACCESSORYMANAGER` 权限。
2. **没有可对照的降噪参考** —— 手上唯一的荣耀耳机 X6 本身不支持降噪，
   所以**从未见过这台机器上的降噪面板长什么样**。要拿到参照需要一只带降噪的
   荣耀/华为耳机（如 FreeBuds Pro、Earbuds 3 pro、Earbuds X5）。
3. `logcat` 全局搜索 `NoiseControl` / `EarphoneDetails` / `SupportedDeviceSettings`
   **零命中** —— 面板从未被打开过，相关代码路径没有跑起来。
4. `com.hihonor.audioaccessorymanager` 进程**会随耳机连接而启动**（pid 见上），
   说明它确实在监听连接事件，但没有走到 UI。

### 两条可选路径

- **A（需要硬件）**：借一只带降噪的荣耀耳机，抓到真实面板入口与取值，再照着做。
- **B（不依赖参考）**：直接按已逆向出的结构实现——hook
  `SupportEarphoneListOuc$EarphoneBaseInfo.getProductId()` 让第三方耳机返回 `FCPG`
  → 加载 `uiconfig/FCPG.json` 的 `homepage.noiseControl`（4 档）→
  再 hook `NoiseControlViewModel` 把读写桥接到 SPP RFCOMM。
  风险：`NoiseControlViewModel` 的方法签名和荣耀档位值仍未确认，需要边做边调。

### 其它已确认事实

- 完整 MAC：`YUANDAO OriG in = 18:5C:A1:52:10:36`，`HONOR Earbuds X6 = 1C:C9:92:43:C5:7D`
  （取自 root 读 `/data/misc/bluedroid/bt_config.conf`；`dumpsys` 输出被荣耀掩码）
- KernelSU 3.2.5，`adb shell su -c` 可用
- LSPosed v2.2.0 (7854) 装在 KSU 模块 `zygisk_lsposed`
- **签名注意**：原 `magicorig.jks` 不在仓库（被 `.gitignore` 排除），
  本机按 `build.gradle.kts` 重新生成了一把 → 已装版本需卸载后才能重装

---

## 10. ✅ 面板入口与早退条件（已反汇编定位）

### 打开方式

```bash
# shell 无 ACCESS_BIND_AUDIOACCESSORYMANAGER 权限，必须 root
su -c 'am start -a com.hihonor.audioaccessorymanager.EARPHONE_DETAILS \
       --es mac 18:5C:A1:52:10:36'
```

action `com.hihonor.audioaccessorymanager.EARPHONE_DETAILS` 解析到
`com.hihonor.earphone.homepage.EarphoneDetailsActivity`。

### 为什么之前会自己关掉

`com.hihonor.earphone.ui.activity.BaseActivity.onCreate` 反汇编：

```
invoke-super  FragmentActivity.onCreate
invoke-direct BaseActivity.f0
invoke-virtual Activity.getIntent
invoke-direct BaseActivity.d0:(Intent;)Ljava/lang/String;   → 存进字段 .y
LogUtils.g("BaseActivity", "onCreate: mac = " + ...)
invoke-static  commonutils.c.a:(Ljava/lang/String;)Z        ← 校验
if-nez → 继续
否则: LogUtils.e("BaseActivity", "onCreate: mac is invalid, return")
      invoke-virtual Activity.finish
      return
```

**我们不带 `mac` 启动 → `d0()` 返回 `""` → `c.a("")` 为假 → `finish()`。**

### `d0(Intent)` 完整解码（MAC 来源优先级）

```java
String d0(Intent intent) {
    if (intent == null) return "";
    String mac = intent.getStringExtra("mac");            // 1) key = "mac"
    if (!commonutils.c.a(mac))
        mac = intent.getStringExtra("mac".toUpperCase(Locale.ROOT));   // 2) key = "MAC"
    if (commonutils.c.a(mac)) return mac;
    Bundle b = intent.getBundleExtra("menuParameter");    // 3) bundle.menuParameter.mac
    return b != null ? b.getString("mac", "") : "";
}
```

### 明确的 hook 目标

| 目标 | 作用 |
|---|---|
| `com.hihonor.earphone.ui.activity.BaseActivity.d0(Intent):String` | 让它返回我们的 MAC（或直接在启动 Intent 里带 `--es mac`） |
| `com.hihonor.commonutils.c.a(String):boolean` | MAC 合法性 / 受支持校验，强制返回 `true` |
| `SupportEarphoneListOuc$EarphoneBaseInfo.getProductId()` / `getSupportJsonName()` | 让第三方耳机取到 `FCPG` → 加载带 `homepage.noiseControl` 的 uiconfig |
| `MbbApi.NOISE_LIST`（静态字段） | 值为 `{1, 2, 0}`；另有模式映射 `0,1,2,3,4,-1` |
| `NoiseControlViewModel` | **方法被混淆**成 `R(I)V` / `U(I)V` / `V(I)V` / `W(I)V` / `Y(ChoiceUiInfo,[Integer,I)V` 等，需在面板能打开后再定位 |
| `productconnect.bluetoothlib.c` | 真正下发指令的类，方法全为 `a..z`，不可直接 hook |

### 未完成

- [ ] 带 `--es mac` 启动后**手机已锁屏**，面板是否正常显示尚未确认
- [ ] `commonutils.c.a()` 的具体判定逻辑（是否查 `supported_earphone_device` / 支持列表）
- [ ] `NoiseControlViewModel` 中"设置模式"的确切方法

---

## 11. 进度：作用域已扩到面板进程，MBB 调用流已抓到（2026-10-06 18:26）

### 已完成

1. `scope.list` + `strings.xml/xposedscope` 增加 **`com.hihonor.audioaccessorymanager`**；
   `HookEntry` 增加对应分支 → `HonorEarphoneHook`
2. **无需软重启**：改作用域后 force-stop 面板进程即生效
   （`staticScope=true` 不会自动覆盖已存储的作用域，
   需在 LSPosed 里勾一次新包 —— 已勾）

### 注入与调用流实测

```
I ENTRY LOADED pkg=com.hihonor.audioaccessorymanager first=true
I loadHook pkg=com.hihonor.audioaccessorymanager hook=HonorEarphoneHook
I installed: MbbApi.getSmartConnect(String, y8.c)      ✓
I installed: MbbApi.getSmartConnect(y8.c)              ✓
W install callback.a(int) failed: Cannot hook abstract methods: public abstract void y8.c.a(int)
W install callback.b(Object) failed: Cannot hook abstract methods: public abstract void y8.c.b(Object)
I onHook OK pkg=com.hihonor.audioaccessorymanager hook=HonorEarphoneHook

18:26:21.676 >>> getSmartConnect(mac=18:5C:A1:52:10:36)
18:26:21.683 <<< returned          ← 7ms 返回，请求是异步的
18:26:21.680 >>> ...（3 次并发调用，mac 全部正确）
```

### 关键修正

- **`y8.c` 是接口不是类** —— dexdump 显示 `Interfaces -` + `Superclass Object` 具有误导性，
  但方法 `a(I)V` / `b(Object)V` 是 `abstract`，LSPosed 报
  `Cannot hook abstract methods`。**必须 hook 它的实现类。**
- `MbbApi.getSmartConnect` 本身**快速返回**，真正的失败（错误码 2001）经回调异步回来 ——
  所以拦截 `getSmartConnect` 只能观察"发起"，要观察/伪造结果必须找到具体回调实现。

### 回调实现类候选（都含 `b(Ljava/lang/Object;)V`）

| 类 | 方法 |
|---|---|
| `com.hihonor.productconnect.MbbApiHelper$a` | `a(Ly8/c;)V` `b(Ljava/lang/Object;)V` |
| `com.hihonor.productconnect.MbbApiHelper$b` | `a(Ly8/c;)V` `b(Ljava/lang/Object;)V` |
| `com.hihonor.productconnect.bluetoothlib.e` | `a(Ly8/c;)V` `b(Ljava/lang/Object;)V` |
| `com.hihonor.productconnect.bluetoothlib.d` | `a(Z)V` `b(Ly8/c;)V` |
| `com.hihonor.productconnect.bluetoothlib.f` | `a(Ly8/c;)V` |
| `com.hihonor.productconnect.bluetoothlib.g` | `a(Ljava/lang/Boolean;)V` `b(Z)V` |

`MbbApiHelper` 是非混淆的外层类（含 `$a`~`$j` 十个内部类），**很可能是协议桥的正确切入点**。

### ✅ 已确认：回调实现类与失败时序（18:43 实测）

在拦截点打印**运行时**类名（静态 dexdump 分析会误判）：

```
>>> getSmartConnect(mac=18:5C:A1:52:10:36) cb=f4.b$a@162888365
>>> getSmartConnect(mac=18:5C:A1:52:10:36) cb=f4.b$a@157029545
>>> getSmartConnect(mac=18:5C:A1:52:10:36) cb=f4.b$a@66102251
<<< returned                                     ← 9ms 返回（异步发起）
<<< f4.b$a.onFailed code=2001   ×3              ← 3 秒后超时，三个回调全失败
```

**`b(Object)`（onSuccess）从未触发** —— 我们的耳机不说 MBB，必然超时。

`f4.b$a` 结构（`f4.b` 的非静态内部类）：

| 成员 | 类型 |
|---|---|
| 字段 `a` | `String`（MAC） |
| 字段 `b` | `Context` |
| 字段 `c` | `f4.b`（外部类） |
| `<init>` | `(f4.b, String, Context)V` |
| `a(I)V` | onFailed(code) |
| `b(Object)V` | onSuccess(data) ← **协议桥要伪造的就是它** |
| `c(Boolean)V` | 另一个回调 |

### ✅✅ 已实现 onSuccess 合成，2001 超时消失（18:56 实测）

反汇编拿到载荷类型后**已改行为**（不再 `proceed()`）：

```
f4.b$a.b(Object) 是桥方法，6 码字：
    0e20d8: check-cast v1, Ljava/lang/Boolean;                       ← 载荷 = java.lang.Boolean
    0e20dc: invoke-virtual {v0,v1}, f4.b$a.c:(Ljava/lang/Boolean;)V
    0e20e2: return-void
f4.b$a.a(int) 只是打日志 "getSmartConnect onFailed"，并不 finish()
```

运行结果：

```
18:56:43.663 >>> getSmartConnect(mac=18:5C:A1:52:10:36) cb=f4.b$a → 合成成功
18:56:43.663 <<< f4.b$a.onSuccess data=java.lang.Boolean      ← 类型确认
18:56:43.665 synthesized onSuccess(Boolean.TRUE) on f4.b$a
```

**对比修复前**（18:43）：3 个回调在 3 秒后全部 `onFailed code=2001`；
**修复后**：`onSuccess` 立即返回，`onFailed 2001` **完全不再出现**。

同时 `AudioAccessoryManager:b: --response not success` 与
`AudioAccessoryManager:c: --get device info error` 也从 logcat 消失。

### ⚠️ 仍未解决：第二条 MBB 通道 `5a0107`（设备信息）

`getSmartConnect` 的 2001 已被我桥接掉，但面板进程还有**另一条**失败路径
（走 `ResultListenerAdapter`，不是 `f4.b$a`）。19:12 实测完整时序：

```
19:12:49.794 BluetoothSppManager init mac=18:5C:*:*:36
19:12:49.794 checkSharedFeature enter
19:12:49.794 getChannel : 18:5C:*:*:36
19:12:49.794 MBB -- MbbProtocolMultiChannelApi: sendCommand.
19:12:49.794 registerMbbChannel sendCommonData
19:12:49.794 IConnectManager: Send: 0107010002000300...002200
19:12:49.797 MBB: Send data true, command = 5a0107          ← tag 5a0107
19:12:49.797 Settings calling: checkIsHwHeadphones          ← 「是否华为/荣耀耳机」判定
19:12:49.797 reportHiviewInfo deviceInfo is null
19:12:49.797 refreshSupportDeviceList current version：1.10.26.105
19:12:50.293 getConfigServerUrl mConfigServerUrl is null    ← 拉取支持列表配置失败
19:12:50.293 DownloadRunnable run()1 / run()2 / run()3      ← 重试 3 次
19:12:54.800 5a0107 last send is time out.                  ← 5 秒超时
19:12:54.800 ResultListenerAdapter onFailed2001
19:12:54.800 --get device info error
```

**结论**：面板退出的直接原因是 `--get device info error`（tag `5a0107`），
与 `getSmartConnect` 是**两条独立通道**。

**下一个 hook 点**：`MbbProtocolMultiChannelApi` 的 tag `5a0107` 应答
（以及 `AudioAccessoryManager:c` 的 `--get device info error` 上游）。
另有两个可疑点可一并处理：
- `checkIsHwHeadphones` —— 决定是否按荣耀自家耳机渲染
- `refreshSupportDeviceList` + `mConfigServerUrl is null` —— 支持列表走云端配置，
  断网/无 URL 时会失败；本地已有 `assets/earphone_support_list_builtin.json` 可作替代源

### 面板 UI 的视觉确认方法（重要教训）

- `dumpsys topDisplayFocusedRootTask` **不可靠**：它报 AAM 任务聚焦，
  但实际画面是 Settings 的 `DeviceProfilesSettingsActivity`
- 准确判据是 `dumpsys activity activities | grep EarphoneDetailsActivity` ——
  它**已经不在 dumpsys 里**，说明 Activity 启动后确实退出了
- 截图前必须 `input keyevent KEYCODE_WAKEUP` + `input swipe 600 2300 600 700 200`，
  否则拿到的是锁屏（`wm dismiss-keyguard` 对人脸/指纹锁无效）

### 🔑 关键资产：`MbbApi` 查询包装类 × 载荷类型地图

每个 `MbbApi.getXXX(y8.c)` 都配一个内部包装类，形态统一：
`<init>(y8.c)` + `a(I)V`(onFailed) + `b(Object)V`(桥，`checkcast → c(载荷)`) + **`c(载荷)V`**。
**`c` 的参数类型就是该查询的载荷类型** —— 这是伪造应答的直接依据。

| 包装类 | 载荷 `c(...)` | 推测 |
|---|---|---|
| `MbbApi$a` `$c` `$s` `$u` `$w` `$x` | `Integer` | 数值型（版本号 / 档位 / 电量）|
| `MbbApi$o` | `String` | 名称/版本串 |
| `MbbApi$n` | `Boolean` + `Boolean` | 开关（`getSmartConnect` 同族）|
| `MbbApi$b` | `z3/m` | 结构体 |
| `MbbApi$c0 $e0 $g0 $b0` | `z3/g` | 结构体族 A |
| `MbbApi$g $h $k $d0` | `z3/e` | 结构体族 B（疑似设备信息）|
| `MbbApi$d $e $f` | `z3/d` | 结构体族 C |
| `MbbApi$i` | `z3/l` | |
| `MbbApi$j` / `$r` | `h3/i` / `h3/g` | |
| `MbbApi$m` / `$t` | `f3/c` | |
| `MbbApi$q` | `g3/a` | |

另有 `MbbApiHeper$a..j`（注意 Honor 拼写是 `Heper` 不是 `Helper`）同构。
**与 `getSmartConnect` 同构的 `f4.b$a` 只是其中之一。**

### `5a0107` 在更下层（不经过 MbbApi 的 y8.c）

日志里的 tag `MbbProtocolMultiChannelApi` / `ResultListenerAdapter` 是**日志 TAG 字符串，不是类名** ——
在 dex 里只作为字面量存在（正则搜类描述符无结果），实际类已混淆。
链路是：

```
IConnectManager → MbbProtocolMultiChannelApi.sendCommand(tag=5a0107)
  → ResultListenerAdapter.onFailed(2001) → AudioAccessoryManager:c "--get device info error"
```

即 **`IConnectManager` / 通道层**，在 MbbApi 之下。下一步应 hook 这一层的 tag 分发，
或 hook `AudioAccessoryManager:c` 的 `--get device info error` 上游。

### 🎯 定位：`--get device info error` 的归属类 = `y2.c$b`

用**字符串→类归属**法（dexdump -d 单个 dex 后按 `Class descriptor` 分组）：

| 日志串 | 所属类 |
|---|---|
| `get device info error` | **`y2.c$b`** |
| `refreshSupportDeviceList` | `com.hihonor.commonutils.o0` |

`y2.c$b` 结构（`y8.c` 风格回调，与 `f4.b$a` 同构）：

```
field a : Ly2/c;
<init>(Ly2/c;)V
a(I)V                → const-string "get device info error"   ← onFailed
b(Object)V                                                  ← onSuccess 桥
c(Lf3/d;)V           → 载荷类型 = f3.d                        ← 要伪造的就是它
```

### 🎯🎯 更关键：`y2.c` 就是「是否受支持」的判定，且有第三方兜底 modelId

```java
y2.c.f(Bundle) / g(Bundle)  中出现：
    "third_device_result" / "result"
    "Current device's modelID： "
    "Current device's modelID is supported by AudioKit:"
    "00VVD9"                                     ← 与 baseSupportDeviceList.json 里
    "Current device's modelID is not supported by AudioKit:"    Third 的 modelId 完全一致
    y2.c.h(String):boolean
    y2.c.i(Bundle,int,Messenger)   （"MenuKey"/"status"/"progress"）
```

**这解释了整条链**：面板查 modelID → `00VVD9` 才算 Third 支持 → 我们的耳机 modelID 不在
支持列表 → `not supported` + `get device info error` → 面板退出。

**因此有两条可选路线（比逐个 MbbApi 合成更省力）**：

- **A（推荐）** hook `y2.c` 的 modelID 判定，让我们的 MAC 对应到 `00VVD9`（Third）
  → 走 `assets/Third.json` 的本地菜单配置，绕开云端与 MBB 设备信息
- **B** hook `y2.c$b.a(I)`（onFailed），伪造 `c(f3/d)` 成功 —— 需先反汇编 `f3/d` 字段

### ⚠️ 再次更正：`y2.c.h` 改写生效，但**面板仍在退出**

19:33 实测：

```
19:33:55.972 >>> getSmartConnect → 合成成功
19:33:56.147 y2.c.h(arg=18:5C:A1:52:10:36) => false  强制改写为 true   ← 改写确实触发
19:34:01.182 y2.c$b.onFailed code=2001                                ← 5 秒后设备信息仍超时
```

**重要教训（第二次栽在同一个坑）**：我一度用「`EarphoneDetailsActivity` 在 dumpsys 里存在 30 秒」
判定面板存活 —— **错的**。`dumpsys activity activities` 里它出现的是
`mLastPausedActivity: ... EarphoneDetailsActivity t-1 f`，
`t-1` 表示已脱离任务栈（正在结束）。轮询匹配到的是**已暂停/正在销毁**的记录。

**唯一可靠判据**是 `topResumedActivity` —— 它始终是
`com.android.settings/.Settings$DeviceProfilesSettingsActivity`，
即面板起来后立刻 finish，把焦点还给 Settings。

### 结论

`y2.c.h`（按 MAC 判定是否受支持）**不是唯一卡点**；即使强制 `true`，
`y2.c$b` 的设备信息查询仍然 5 秒超时（`onFailed 2001`）→ 面板 finish。

### 下一步（明确）

必须处理 `y2.c$b` 这条（与 `f4.b$a` 同构）：

- 载荷类型已知：`c(Lf3/d;)V` → 要伪造成功必须先反汇编 `f3/d` 的字段
- **更快的替代**：hook `y2.c$b.a(int)`（onFailed）**直接吞掉、不 `proceed()`**，
  阻止错误向上传播导致 `finish()`。风险是面板可能因等不到数据而空白，
  但比直接退出更接近可观察状态，且不依赖伪造 `f3/d`

### 下一步

1. **反汇编 `f4.b$a.b(Object)V` 找 `checkcast`** → 得到 `b` 期望的真实数据类型
   （这是唯一还没拿到的量；`onSuccess` 从未触发，无法靠观察得到）
2. 拿到类型后，在 `getSmartConnect` 拦截点**不调 `proceed()`**，
   直接 `cb.b(伪造数据)` → 面板立刻收到成功，绕开 3 秒超时
3. 把数据源换成 NiceHCK `0x4E` SPP 帧（`RfcommController` 已有电量/ANC）
4. 最终形态：MBB 请求 ↔ SPP 应答的双向翻译

---

## 12. 已修掉的运行期问题（均有日志实证）

| 问题 | 证据 | 修复 |
|---|---|---|
| `A2dpService` 继承链无 `mContext` | `mContext not available` | 实例本身即 `ContextWrapper`，三级兜底 |
| MagicOS 无 `CentralSurfacesImpl` | `failed to hook CentralSurfacesImpl.start` | 改 `SystemUIService.onCreate` + 候选列表 |
| 蓝牙进程无权改状态栏图标 | `RemoteException ... enforceStatusBar` | 图标交给 SystemUI hook，蓝牙侧只记日志 |
| `Log.module` 赋值过晚 | `ENTRY LOADED` 永远丢失 | 移到 `onPackageReady` 最前 |
| `android.util.Log` 不进 logcat | buffer 回溯到 17:05 却查不到 17:18 调用 | 全部改走 `module.log()` |
| `SettingsHeadsetHook` 假成功 | `HeadsetIDConstants` 不存在 | 先探测类存在性再挂钩，如实报告 |


---

## 13. ✅ 面板已成功打开 —— 卡点移到 MBB 协议（16:32 实测）

用第 10 节的命令启动后，`com.hihonor.audioaccessorymanager`（pid 25950）日志：

```
HnActivityTransitionStateEx: onStop : com.hihonor.earphone.homepage.EarphoneDetailsActivity@6cd4118
D AudioAccessoryManager:MacActivityManager: --removeActivity: [18:5C:*:*:36] com.hihonor.audioaccessorymanager
```

**`18:5C:*:*:36` 被接受了 —— MAC 解析 + 合法性校验全部通过，面板真的开起来了。**

### 新的真正卡点：MBB 协议握手失败

```
I AudioAccessoryManager:b: --response not success
E MbbApi: --getSmartConnectByMac onFailed = 2001
I AudioAccessoryManager:b: --getSmartConnect onFailed
E MBB -- ResultListenerAdapter: ResultListenerAdapter onFailed2001
I MBB -- MbbProtocolMultiChannelApi: 5a2b6b last send is time out.
I MBB -- MbbProtocolMultiChannelApi: 5a0107 last send is time out.
D AudioAccessoryManager:c: --get device info error
```

流程：面板起来 → 通过 **MBB 多通道协议**（`MbbProtocolMultiChannelApi`）向耳机查设备信息
（tag `5a2b6b`、`5a0107`）→ 我们的 NiceHCK 耳机不说 MBB → 超时 → 错误码 **2001**
→ `get device info error` → 面板退出。

### 因此最终 hook 方案（在 MbbApi 层做协议转换）

| Hook 点 | 做什么 |
|---|---|
| `MbbApi.getSmartConnectByMac`（方法名未混淆） | 拦截 → 用 `RfcommController` 的状态**伪造成功响应** |
| `MbbProtocolMultiChannelApi` 的 tag `5a2b6b` / `5a0107` | 用 SPP 侧电量/ANC 数据替代 MBB 应答 |
| `MbbApi` 结果回调里的 `2001` | 改写为成功，阻止面板 `finish()` |
| `BaseActivity.d0` / `commonutils.c.a` | 保持（已通过 `--es mac` 绕过，正式实现时由模块注入 MAC） |

**协议转换是核心**：UI 以为在跟荣耀耳机说话，模块负责把 MBB 请求翻译成 SPP RFCOMM
（NiceHCK `0x4E` 帧），再把应答翻回 MBB。

---

## ⚠️ 操作约束（务必遵守）

- **不要重启手机。** 需要重启时必须先征得同意，且只用**软重启**
  （`setprop ctl.restart zygote`，不重启内核，KernelSU/Zygisk 模块保持加载）。
- 已在 LSPosed 中启用模块但 **systemui 未重启** —— hook 尚未生效，需软重启后验证

---

## 14. 实测：设备已归类为 Third，但 `Third.json` 没有降噪配置（19:49）

```
o0.i(arg=00VVD9) => true  强制改写为 true      ← isSupportDeviceListOUC(00VVD9) 本就 true
o0.d(productId=Third) => Third                 ← productId 已是 Third
```

`com.hihonor.commonutils.o0` 方法表（dex 反汇编）：

| 方法 | 签名 | 含义 |
|---|---|---|
| `i` | `(String):boolean` | `isSupportDeviceListOUC` — 日志 `"is supported."` |
| `d` | `(String):String` | `getBaseJsonNameFromProductId` — **读 `assets/<name>.json` 顶层菜单表** |
| `e` `f` | `(String):String` | `"ProductId list not initialized."` |
| `g` | `(String):boolean` | |
| `h` | `():boolean` | `isNeedRefrshShared` / `oucCloudConfigVersion` |
| `j` | `(String):boolean` | `isOverseaShowAISpace` |

### 结论

- 设备被判定为 **modelId `00VVD9` = Third**（`baseSupportDeviceList.json` 的第三方兜底项）
- `Third` → `assets/Third.json`，**只有** `SWITCH_CONNECT_DIALOG` + `goto_magichome`，**没有 `noiseControl`**
- **`FCPG.json` 只在 `assets/uiconfig/` 子目录，顶层没有** → 改写 `o0.d` 返回 `FCPG` 会加载不存在的资源（已回退为纯观察）
- **正确的 hook 点是 uiconfig 加载器**（反汇编见 `j2.a(String) → UiConfigLite`、`g1/a`），
  让 `00VVD9/Third` 命中 `uiconfig/FCPG.json`（含 `homepage.noiseControl.noiseSwitchNum=4`）

### 关闭机制（已定性）

`finish()` 调用栈：`LiveData.d → c.a → EarphoneDetailsActivity.h0 → p0 → m0 → finish()`
—— 关闭来自 **LiveData 观察者**，不是 MBB 超时直接触发。已实现临时拦截（诊断用）。

### 启动方式（易错）

`am start -a com.hihonor.audioaccessorymanager.EARPHONE_DETAILS` **会解析到**
`com.android.settings/.Settings$DeviceProfilesSettingsActivity`（多接收者），
**必须用显式组件** `-n com.hihonor.audioaccessorymanager/com.hihonor.earphone.homepage.EarphoneDetailsActivity`。
`am start -W` 是唯一可靠的成功判据（`Status: ok` + `TotalTime`）。

### Round 4：找到 uiconfig 加载器，但它没被调用

`j2.a` 的反汇编：

```
j2.a.r0:(Ljava/lang/String;)Lcom/hihonor/earphonesdk/uiconfig/entity/UiConfigLite;
   "uiconfig/" + prodId + ".json"
   "loadAssetsConfig: assets file empty for prodId: "
   "loadAssetsConfig: json parse error for prodId: "
   "loadAssetsConfig: success for prodId: "
j2.a.q:()V    ← 初始化入口，调 r0，并调 i2.x.q0(File) 解析
   "AirpodsUiConfig init for prodId: "
   "init: local config invalid, fallback to assets"
   "init: no local and assets config, sync download from cloud"
```

**9 个观察点全部 installed**（19:54:31），但实测：

```
19:54:32.317 EarphoneHelper: --EarphoneInfo{
    mMac='18:5C:*:*:36', mModelId='00VVD9', mProductId='Third',
    mDeviceType=HONOR_NEW, mEarphoneType=UNKNOWN, mUiConfigVersion='null' }
```

- `j2.a.r0(prodId=...)` **一次都没触发** → 配置加载路径未走到
- `mUiConfigVersion='null'` → **UiConfigLite 从未加载**
- `finish()` 仍由 `LiveData.d → c.a → h0 → p0 → m0` 触发（19:54:32.328）

**下一轮**：`r0` 的唯一调用方是 `j2.a.q()`。需确认 `q()` 是否被调用 ——
若也未调用，说明配置初始化发生在别处（可能在 `EarphoneHelper` 之前或另一个进程），
应回头 hook `EarphoneHelper` / `EarphoneInfo` 的构造点，或直接给
`mUiConfigVersion` 赋非空值来驱动配置加载。

### Round 5：根因链已完整，但 `checkConnectionState` 的值有陷阱

**根因链（19:57:59 实测，顺序确定）**：

```
b2.a: checkConnectionState: false, mac=18:5C:*:*:36
b2.a: resultBundle is null or getDeviceInfo failed      ← 设备信息拿不到
b2.a: manufacturer passed in is empty, use default type
b2.a: EarphoneInfo{mDeviceName='', mModelId='00VVD9', mProductId='Third',
                   mDeviceType=HONOR_NEW, mEarphoneType=UNKNOWN, mUiConfigVersion='null'}
y2.c$b.onFailed code=2001 ×5                            ← MBB 设备信息全失败
```

`b2.a` = 日志 TAG `EarphoneHelper`（字符串归属法确认）。方法表：

| 方法 | 签名 | 含义 |
|---|---|---|
| `a` | `(String):int` | 含 `"checkConnectionState: "` 与 `"operation not supported, treat as disconnected, mac="` |
| `d` | `(String):Bundle` | 含 `"resultBundle is null or getDeviceInfo failed"` ← **getDeviceInfo** |
| `l` | `(String):boolean` | |
| `c/e/f/g/j/i/h/b` | | 各类查询 |
| `k` | `(Application):void` | 初始化 |

**⚠️ 陷阱**：hook `b2.a.a` 实测 **original = 2**（不是 0/false），
而 app 自己打的日志却是 `checkConnectionState: false` —— 说明
**日志里的 `false` 来自 `a()` 内部的另一个布尔量（或 `l()`），不等于返回值 2**。
枚举很可能是 `0=断开 / 1=连接中 / 2=已连接`，**2 本来就是"已连接"**。
我一度强制改成 `1`，反而可能把状态降级 —— **已回退为纯观察并重新安装**。

**教训**：`Log` 打印的布尔与方法返回值不是同一个量，不能拿日志里的 `false`
去推断返回值该改成什么。下一轮先观察 `b2.a.l(String):boolean` 与 `b2.a.d(String):Bundle`。

### Round 6：`getDeviceInfo` 返回 null —— 配置加载仍未触发

观察结果（20:07 / 20:09）：

```
b2.a.checkConnectionState(mac=18:5C:A1:52:10:36) => 2          ← 保持观察，不改
b2.a.l(String) 装了但从未调用                                   ← false 不来自它
b2.a.getDeviceInfo(mac=18:5C:A1:52:10:36) => bundle=null       ← 真正的空值来源
    ↓ 替换为空 Bundle 后
20:09:55 b2.a.getDeviceInfo => null 替换为空 Bundle（观察用）   ← 干预已生效
```

**但 `j2.a.r0`（uiconfig 加载器）依然一次都没触发**，说明配置加载的门在别处。

app 侧新现象（20:10）：

```
AudioAccessoryManager:ImageLoadUtils: --Third res state === REQUEST_SUCCESS   ← Third 资源已加载
okhttp: connection to https://configserver.platform.hihonorcloud.com/ ...    ← 在拉荣耀云配置
AudioAccessoryManager:b: --response not success  ×N                          ← 某些响应仍失败
SecurityComp10105302: createSocket ...                                       ← TLS 连接在建
```

**仍未解决**：`uiconfig` 加载门未打开 → `mUiConfigVersion='null'` → 无 `noiseControl`。
下一轮应定位 **`j2.a.q()` / `i2.x` 的初始化时机**（`i2.x.b` 字段就是持有 `UiConfigLite` 的地方），
或查清 `--response not success` 对应的请求为何失败（可能是它挡住配置加载）。

### Round 7：`j2.a.q()` 是死代码；拿到 getDeviceInfo 的 Bundle key

**`j2.a.q()`（uiconfig 初始化）在全部 6 个 dex 中无任何调用方** ——
它内部调 `r0`，但没人调它，所以 `r0` 永远不触发。`q()` 只能通过反射或其它入口驱动。

`--response not success` 归属类：**`s0.b$a`**。

反汇编 `b2.a.d(String):Bundle` 拿到它写入的 key：

```
"device_general_name"        → ""
"device_submodel"            → "0"
"device_uiconfig_version"    → ← mUiConfigVersion 的真正来源
"device_earphone_manu"
"00VVD9"
```

已按这些 key 合成 Bundle（替代 null，20:14:05 生效）：

```kotlin
Bundle().apply {
    putString("device_general_name", "YUANDAO OriG in")
    putString("device_submodel", "0")
    putString("device_uiconfig_version", "1")
    putString("device_earphone_manu", "")
    putString("modelId", "00VVD9")
}
```

**结果：`j2.a.r0` 仍未触发** —— 说明 uiconfig 加载的门不在 `getDeviceInfo` 返回值上，
而在 `j2.a.q()` 的调用链（当前无人调用）。

**下一轮方向（二选一）**：
- A：主动触发 `j2.a.q()` —— 需要拿到 `j2.a` 实例（其 `<init>(Lb2/b;)V`，`b2.b` 来自 `b2.a.c(String)`）
- B：绕过 `j2.a`，直接给 `i2.x.b` 字段注入从 `uiconfig/FCPG.json` 解析出的 `UiConfigLite`
  （`i2.x.q0(File): UiConfigLite` 就是解析器，可用它解析 APK 内 assets）

### Round 8：`i2.x` 访问器已定位，FCPG 注入点已装但未触发

`i2.x`（Superclass `i2.y`）结构：

| 成员 | 签名 | 作用 |
|---|---|---|
| 字段 `b` | `UiConfigLite` | **配置持有者（因 q() 死代码恒为 null）** |
| `B` | `(UiConfigLite): UiConfigLite$Homepage` | 取 homepage |
| `E` / `K` | `(Homepage): NoiseControl` | **取降噪配置** |
| `H` / `J` | `(NoiseControl): Integer` | 档位数 |
| `A` / `D` / `C` | FunctionControl/MoreSetting/BaseInfo 列表 | |
| `I` | `(String): boolean` | |
| `<init>` | `(Lb2/b;)V` | |

实现（走 B 路线）：用 **app 自己的 Gson** 解析 AAM 的 `assets/uiconfig/FCPG.json` →
`UiConfigLite`，再 hook `i2.x.B(UiConfigLite)`：入参为 null 时反射取 `homepage` 返回，
绕过整条 `j2.a.q()` 死代码链。

**实测（20:18:55）**：`installed: i2.x.B(UiConfigLite) [注入 FCPG 配置]` 成功，
但 **`i2.x.B(null) → 注入 FCPG homepage` 未出现** —— `B` 在观察窗口内根本没被调用，
说明 UI 在调用 `B` 之前就已经走了关闭路径（`topResumedActivity` 仍是 Settings）。

**下一轮**：`B` 未被调用意味着 UI 读配置的入口更早。需改从
`i2.x.E(Homepage)` / `i2.x.K(Homepage)` 或 UI 侧 ViewModel 入手，
或先解决 `topResumedActivity` 恒为 Settings 的问题（面板可能始终没真正 resume）。

### Round 9：⚠️ 重大框架更正 —— 面板不是关闭，而是**转发**

`dumpsys activity activities` 里同一个 task `#16021 (A=1000:com.hihonor.audioaccessorymanager)`：

```
* Hist #0: .../EarphoneDetailsActivity
    state=STOPPED  finishing=false  rootOfTask=true        ← 没有 finish！
* Hist #1: com.android.settings/.Settings$DeviceProfilesSettingsActivity
    launchedFromPackage=com.hihonor.audioaccessorymanager
    Intent { act=com.hihonor.intent.action.DEVICE_PROFILES_SETTINGS }
```

**结论（推翻 Round 2/3 的判断）**：

- 面板**从未 finish**（`finishing=false`），它 `startActivity` 跳到荣耀自己的
  `com.hihonor.intent.action.DEVICE_PROFILES_SETTINGS`，然后 `STOPPED` 让位
- **真正的荣耀降噪 UI 就在 `Settings$DeviceProfilesSettingsActivity` 里**
- 我之前拦截 `finish()` 是基于错误前提（那段调用栈来自更早的流程）；
  `topResumedActivity` 恒为 Settings 不是"面板没打开"，而是**面板把 UI 交给了 Settings**

**该页面实际内容**（本轮截图，`com.hihonor.settingslib.SubSettings` 承载）：

```
重命名 / 设备类型=耳机 / 高音质=LHDCV5
通用设置 ›                    ← 新增行，15:57 基线里没有
通话音频 / 媒体音频 / 共享联系人
蓝牙自动连接 / 音量同步 / 来电铃声同步
取消配对
```

**"通用设置"是新增的** —— 说明我的改写**确实在改变该页面的行列表**，方向正确，但还没长出降噪行。

**下一轮（目标明确）**：找出该页面行列表的来源（很可能就是 `o0.d` 读的顶层菜单表
`assets/<name>.json`，或 Honor 自己的 `DEVICE_PROFILES_SETTINGS` 行装配逻辑），
让本设备命中含降噪项的配置。**不再需要 `finish()` 拦截**（可移除）。

### Round 10：页面层级已走通，「通用设置」里没有降噪

调用链（`am start -W` 实证，`Status: ok`）：

```
am start -n .../EarphoneDetailsActivity --es mac 18:5C:A1:52:10:36
  → 转发 com.hihonor.intent.action.DEVICE_PROFILES_SETTINGS
  → Settings$DeviceProfilesSettingsActivity（承载于 com.hihonor.settingslib.SubSettings）
      行：重命名 / 设备类型=耳机 / 高音质=LHDCV5 / **通用设置 ›** / 通话音频 / 媒体音频
          / 共享联系人 / 蓝牙自动连接 / 音量同步 / 来电铃声同步 / 取消配对
  → 点「通用设置 ›」→ SubSettings「通用设置」页
      行：通话音频 / 媒体音频 / 共享联系人 / 蓝牙自动连接 / 音量同步
          / 来电铃声同步 / 低延迟        ← 只是通用项分组，**无降噪**
```

**「通用设置 ›」是新增行**（15:57 基线没有）—— 证明我的改写确实在改变行装配，
但**降噪行仍未出现**，且它不在「通用设置」子页里。

**下一轮**：
1. 该主页面的行装配来源 —— `Third.json` 只有 `SWITCH_CONNECT_DIALOG` +
   `goto_magichome`，**没有**「通用设置」，所以行列表不来自 `Third.json`；
   需在 `Settings$DeviceProfilesSettingsActivity` / `hono.settingslib` 里找装配点
2. 移除基于错误前提的 `finish()` 拦截。

### Round 11–14 汇总（逆向成果，务必保留）

**① 荣耀降噪 UI 全套在 `com.android.settings` 里**（字符串归属法扫 dex）：

```
BluetoothNoiseControlSettings            ← 降噪设置页（fragment，未在 manifest 声明，
                                            故 am start -n 报 "does not exist"）
NoiseControlSettings (+$a)
hwcontrollers/ancsettings/AncSwitchController             ← 降噪开关行
hwcontrollers/profilesettings/NoiseControlController      ← 降噪控制行
hwcontrollers/profilesettings/NoiseControlPanelController ← 降噪面板行
hwcontrollers/profilesettings/LongPressNoiseController / LongPressNoiseTipController
hwcontrollers/generalsettings/ProfileContainerPreferenceCategoryController ← 「通用设置」容器
hwcontrollers/profilesettings/HighQualityMenuController / RenameDeviceController / LdacSettingsController
com.android.settings.bluetooth.BluetoothAncSettings       ← 另一个降噪入口
```

行装配 = Android Settings 标准 **Preference Controller** + `getAvailabilityStatus()`（int, AVAILABLE=0）。

**② 修掉致命 bug**：`SettingsHeadsetHook` 原用 `.onFailure { Log.w(...); return }` ——
**非局部 `return` 直接退出整个 `onHook()`**，其后所有 hook 都没装上
（日志只有 `target class absent` 一行、无任何 `installed`）。已改为 `isSuccess` + if/else。

**③ 三个降噪 Controller 已 hook 成功**（21:16:05 日志）：
```
installed: AncSwitchController / NoiseControlController / NoiseControlPanelController
           强制可用 + displayPreference 观察
```
但 `getAvailabilityStatus` 与 `displayPreference` **一次都没触发** → 未走到。

**④ `DeviceProfilesSettings.onCreate` 反汇编**（classes2.dex 行 849632）：

```
0000 new LdacSettingsController → List.add(field .n)     ← .n 就是 controller 列表
0012 new profilesettings.a      → List.add(.n)
0026 invoke-super SettingsPreferenceFragment.onCreate
002f const "DEVICE_TYPE" / "from_bt_settings" / "DEVICE_ID"
005f getArguments()
0063 if-nez args → 0069
0065   SettingsPreferenceFragment.finish()   ← ★ arguments==null 直接 finish()
0068   return-void
0069 args.containsKey("from_bt_settings"); 0075 args.containsKey("DEVICE_ID")
01b7 new NoiseControlController      → iput .O
01c2 new NoiseControlPanelController → iput .P
b3/j3: NoiseControlController.displayPreference(PreferenceScreen)
w3   : NoiseControlController.release()
q2   : NoiseControlPanelController.onDataReceive(String,[B)
```

- controller 列表 = 字段 **`.n: java.util/List`**
- **参数取自 Fragment `getArguments()`，不是 Intent extras** —— 与 `BaseActivity.d0`
  读 Intent extra `mac` 是**两套不同通道**，面板转发时需正确桥接

**下一轮**：dump `onCreate` 在 `0096 ~ 01b7` 之间的分支，找 `.n.add(NoiseControlController)`
之前的守卫（大概率是"设备是否支持降噪 / `UiConfigLite.noiseControl`"判定）。

### Round 14–15：降噪行的触发条件 = **收到设备降噪数据后的 Runnable**

`onCreate` 完整早退点（反汇编 classes2.dex 行 849632）：

```
00b6 addPreferencesFromResource(0x7f16003f)   ← 主布局 XML
00cb if-nez manager → 00d6
00d2 "mManager == null" → finish() return                       ← 早退 ②
00d6 manager.c().m(address) → q0(CachedBluetoothDevice) → 字段 .a
00e0 if-nez .a → 00eb
00e7 "Device not found, cannot connect to it" → finish() return  ← 早退 ③ ★
0101 new RenameDeviceController
01b7 new NoiseControlController → .O     ← 实例化（早退点①是 0065 getArguments()==null）
01c2 new NoiseControlPanelController → .P
```

**页面渲染出了「重命名/设备类型/高音质」→ 证明 onCreate 越过 00eb、走到 01b7/01c2，
两个降噪 Controller 确实被创建了。但 `displayPreference` / `getAvailabilityStatus` 都没触发。**

`displayPreference` 的唯一调用链（本次定位）：

```
DeviceProfilesSettings$$ExternalSyntheticLambda2.run()  → M2(Settings,[B)V  → j3([B)V → NoiseControlController.displayPreference(PreferenceScreen)
DeviceProfilesSettings$a$a.run()                        → P2(Settings,String)V → b3(String)V → NoiseControlController.displayPreference
```

（`M2`/`P2` 是**静态合成 lambda 本体**，签名 `(LDeviceProfilesSettings;[B)V` / `(LDeviceProfilesSettings;Ljava/lang/String;)V`；
`j3`/`b3` 还会调 `NoiseControlPanelController.displayPreference`，`q2` 调 `onDataReceive(String,[B)`。）

### 🔑 结论

**降噪行不是「设备支持就显示」，而是「收到设备降噪数据 → 跑 Runnable → displayPreference」。**
我们的耳机说不出 MBB 降噪数据 → **Runnable 永不执行 → 行永不出现**。

**因此必须补的是"提供降噪数据"**，两条路：
- **A** hook `M2`/`P2`（或两个 lambda 的 `run()`）→ 直接触发，并喂入伪造数据
- **B** 回到 MBB↔SPP 协议桥：把 NiceHCK `0x4E` SPP 帧的 ANC 状态翻译成荣耀期望的字节/字符串

**下一轮**：hook `DeviceProfilesSettings$$ExternalSyntheticLambda2.run` 与
`DeviceProfilesSettings$a$a.run`，先确认它们是否被调度（有无 post），再决定 A/B。

### Round 16：✅ 触发点精确定位 = `DeviceProfilesSettings$a.onGetDataSucceed`

实测（21:35:52）：两个 Runnable 的 `run()` **hook 全部安装成功**，但**一次都没执行**；
`displayPreference` / `getAvailabilityStatus` 同样零触发 → **它们从未被调度**。

创建点反汇编：

```
DeviceProfilesSettings$a.onGetDataSucceed(...)   |0028 new-instance DeviceProfilesSettings$a$a
                                                 → P2(Settings,String) → b3 → displayPreference
DeviceProfilesSettings.q2(String, byte[])        |0019 new-instance DeviceProfilesSettings$$ExternalSyntheticLambda2
                                                 → M2(Settings,[B) → j3 → displayPreference
```

**结论（因果链完整闭合）**：

```
MBB 设备信息查询失败（2001）
  → DeviceProfilesSettings$a.onGetDataSucceed 从未触发
  → 两个显示用 Runnable 从未被 new / 调度
  → NoiseControlController.displayPreference 从未调用
  → 页面不长出降噪行
```

**`onGetDataSucceed` 就是最终开关。** 它的上游正是 Round 5–7 定位的
`y2.c$b`（device info，错误码 2001）与 `b2.a.d`（`getDeviceInfo` 返回 null）。

**下一轮（A/B 二选一，优先 A）**：
- **A** hook `DeviceProfilesSettings$a.onGetDataSucceed`：先纯观察确认"确实从未被调用"；
  再研究能否构造/取到 `DeviceProfilesSettings$a` 实例自行触发
- **B** 回到协议桥：让 `y2.c$b` 收到伪造的 `f3/d` 成功数据，使 `onGetDataSucceed` 自然触发

### 🎯 Round 17：触发链**首次全通**（日志实证）

`DeviceProfilesSettings$a` 方法表：`onGetDataFailed(String)` / **`onGetDataSucceed(String)`** /
`onSetConfigFailed(String,String)` / `onSetConfigSucceed(String,String)`，
字段 `a : DeviceProfilesSettings`，**在 `DeviceProfilesSettings.<init>` 偏移 0034 被 new**。

实现：hook `$a.<init>` 捕获实例 + hook `onGetDataSucceed` 观察 + hook `Fragment.onCreate`
在 `postDelayed(1500ms)` 里主动 `invoke(onGetDataSucceed, mac)`。

```
21:46:18.122 installed: onGetDataSucceed 观察 + 主动触发
21:46:18.216 捕获 $a 实例 @144357977
21:46:19.732 主动触发 onGetDataSucceed(mac=18:5C:A1:52:10:36)
21:46:19.733 onGetDataSucceed 自然触发 arg=18:5C:A1:52:10:36
21:46:19.733 降噪 Runnable 被执行: DeviceProfilesSettings$a$a          ← 首次执行
21:46:19.736 displayPreference 被调用: NoiseControlController         ← 首次
21:46:19.736 displayPreference 被调用: NoiseControlPanelController    ← 首次
```

**整条链（捕获 → 触发 → Runnable → displayPreference）被完整打通。**

### ❌ 但截图仍无降噪行（21:46）

页面行：重命名 / 设备类型 / 高音质 / 通用设置 / 拦截蓝牙外设自动播放指令 /
连接提示框 / 荣耀智慧空间 / 取消配对 —— **无降噪项**，且 `getAvailabilityStatus`
的「强制可用」日志也**没出现**。

**说明 `displayPreference` 执行了但没有真正把 preference 加进去** —— 最可能：
`NoiseControlController.displayPreference` 内部按 key 从当前 `PreferenceScreen` 找
preference，而 `addPreferencesFromResource` 加载的 XML 里**没有对应 key**
（或需要先有 `UiConfigLite.noiseControl` 才存在的 preference key）。

**下一轮**：反汇编 `NoiseControlController.displayPreference`，看它查找的 **preference key**
是什么，再确认该 key 是否在 `onCreate:00b6` 加载的 XML（0x7f16003f）里。

---

## 🎉 Round 18–20：降噪面板**成功出现**（21:57 实拍）

### 1. `NoiseControlController` 完整结构（dexdump）

```
静态: KEY_BT_NOISE_CONTROL / KEY_BT_NOISE_CONTROL_CATEGORY
      ORDER_NOISE_CONTROL / ORDER_NOISE_CONTROL_CATEGORY / TAG
字段: mActiveDevice(Lx7/a;) / mBluetoothAddress(String)
      mHwPreferenceCategory(HwPreferenceCategory) / mPreference(Preference)
方法: <init>(Context,x7.a) / buildPreference() / buildPreferenceCategory(PreferenceScreen)
      initExtras() / displayPreference(PreferenceScreen) / getAvailabilityStatus()I
      getIntentFilter() / hasAsyncUpdate() / isSliceable() / release()
```
→ **它自己 build preference，不依赖 XML 里的 key**（推翻 Round 17 的猜测）。

### 2. `displayPreference` 最终开关（完整反汇编）

```
0000: if-eqz v2, 0031                 ← PreferenceScreen 为 null → return
0004: mActiveDevice.r():Z             ← ★★★ 最终开关
0008: if-nez v0 → 000b ; 000a goto 0031 ← r()==false 直接 return，preference 永不 add
000b: super.displayPreference
0012: buildPreferenceCategory → PreferenceGroup.Q(category)
0021: buildPreference() + initExtras()
002e: PreferenceGroup.Q(preference)
0031: return
```

### 3. 日志链全通（21:55:00）

```
displayPreference 被调用: NoiseControlController
x7.a.r() => false  强制改写为 true（降噪行最终开关）
强制可用: NoiseControlController                       ← 越过 0008，走到 add
displayPreference 被调用: NoiseControlPanelController
x7.a.r() => false  强制改写为 true
强制可用: NoiseControlPanelController
```

### 4. ✅ 视觉证据（21:57:50 新帧 `f215749.png`）

设备详情页（标题「原道 OriG in」）新增：

```
噪声控制                              更多 ›
   [降噪]      [透传]      [关闭]
```

**荣耀原生三档降噪 UI 出现在第三方耳机页面上。**

### 5. 抓图方法论（本 session 反复踩坑，务必照做）

- `screencap` 会返回**旧帧**（状态栏时间是判据；上一张 21:46 而日志已 21:55）
- 必须：`KEYCODE_WAKEUP` → `input swipe 600 2300 600 700 200` → 等 → `rm -f` 目标
  → `screencap` 到**新文件名** → 核对文件时间晚于最新 hook 日志
- **不要**用 `topResumedActivity` / `topDisplayFocusedRootTask` / `mLastPausedActivity` 判断
  「面板是否打开」—— 三者都误导过（正确判据是 `am start -W` 的 `Status: ok`
  + `dumpsys ... state=` + `finishing=false`）

### 6. 仍待验证

#### Round 21：点击「降噪」**无任何下发**（重要否定）

```
面板在（topResumedActivity = DeviceProfilesSettingsActivity）
input tap 287 1415（降噪按钮）
→ logcat 只有 WiFi 噪声指标（noise=-96），无 NoiseControl/setNoise/ANC 下发
```

按钮是灰色/未选中态 —— 推测 `isEnabled=false`（无回读数据）。

#### Round 22：`Preference.setEnabled` 观察已装

反汇编 `buildPreference()` / `initExtras()` 因 dexdump 输出格式不一致**未能取到**，
改为运行时 hook `androidx.preference.Preference.setEnabled(boolean)` 并打印 `getKey()`。

22:03 实测 tail-20 出现的 key：`PBAP Server` / `bluetooth_low_latency` /
`high_quality_audio` / `HEADSET` / `A2DP` / `device_audio_sharing_switch` ——
**没有出现噪声控制相关 key**（可能是 tail-20 截断，也可能是该 preference 从未调用 setEnabled）。

**下一轮**：全量 grep `setEnabled` 找噪声 key；若确无 → 反汇编 `NoiseControlController.buildPreference`
（改用 `-like` 精确匹配方法头，勿再 `Escape` 正则）看它 setEnabled/onClick 的条件。

- **三个按钮点击后是否真能下发降噪**（灰色 = 尚无回读数据；需接 SPP `0x4E` → `setANCMode`）
- 「更多 ›」子页内容
- 按钮状态回读（需 MBB↔SPP 应答翻译）

---

## Round 24–30：主行入口与降噪控制页已定位（逆向成果）

### 1. `NoiseControlController.buildPreference()`（dexdump 已成功取到）

```
new StatusPreference(mContext)
setKey("key_bt_noise_control")         ← ★ 主行 key（不是 key_denoise！）
setPersistent(false) / setTitle / setSummary
setWidgetLayoutResource(0x7f0d057b)    ← 三档按钮在这个 widget 里
setOrder(410)
// 注意：没有 setEnabled，也没有 setOnPreferenceClickListener
```

**修正 Round 23 的判断**：`key_denoise` / `key_hear_through` / `key_denoise_close`
是 **widget 内的三个按钮**，不是主行 preference；主行是 `key_bt_noise_control`。

### 2. `initExtras()` —— 点击去向 + 必需 extras

```
0005 const-class com.android.settings.bluetooth.BluetoothNoiseControlSettings
000b Preference.setFragment("...BluetoothNoiseControlSettings")  ← ★ 主行点击 → 真正的降噪控制页
000e if (mBluetoothAddress == null) → return                      ← MAC 空则不带 extras
0018 putString("current_device_id",     mBluetoothAddress)
0021 mActiveDevice.f()
0027 putString("current_device_modelid", ...)                     ← ★ modelId
```

### 3. 启动方式（实测）

- `BluetoothNoiseControlSettings` **不是 Activity**（dex 有类、manifest 未声明）→ `am start -n` 报
  `Activity class ... does not exist`
- 正确走 **`com.hihonor.settingslib.SubSettings`** + `--es :settings:show_fragment <类名>`，
  但它 **未导出**，shell 报 `not exported from uid 1000` → **必须 `su -c`**
- 实测 root 启动**成功**（Recent #2 出现该 SubSettings task），extras：

```
--es :settings:show_fragment com.android.settings.bluetooth.BluetoothNoiseControlSettings
--es current_device_id     18:5C:A1:52:10:36
--es current_device_modelid 00VVD9
```

（`00VVD9` 来自 `EarphoneHelper` 实测的 `mModelId='00VVD9'`）

### 4. 面板渲染不稳定的真实原因（修正 Round 25/26 的反复误判）

- Round 25 猜 `getArguments()==null` → **被 Round 26 推翻**：实测
  `args=ok(Bundle[{DEVICE_ID=18:5C:A1:52:10:36}]) activityFinishing=false`
- Round 26 猜"时序" → **Round 27–29 证明是手机正被使用**：
  连续前台 = bilibili → 崩坏：星穹铁道 → 崩铁，`am start` 拉起即被抢占

**结论**：面板与触发链本身是稳定的，不稳定来自**前台被用户应用占用**。

### 5. 待办（下一步）

1. **手机停在桌面 30s** → 重启降噪控制页 → 截图确认
2. 点档位 → 验证是否下发（`RfcommController.setANCMode` → SPP `0x4E` 帧）
3. 按钮状态回读（MBB↔SPP 应答翻译）

---

## 🎉 最终成功：端到端链路全通（用户实测确认）

### 1. 完整链路（每步都有日志/反汇编佐证）

```
荣耀设置页 [噪声控制: 降噪|透传|关闭]
  → NoiseControlController.displayPreference          ← x7.a.r() 强制可用后才 add
  → MultiStateSwitchingPanelPreference.onClick(View)
       tag=view.getTag() → index ; item=a.get(index) ; mode=$b.a(item):B
       → this.Q(mode) ; cb.onMultiStateClicked(index, mode)
  → NoiseControlPanelController.onMultiStateClicked(int, byte)   ← 接口 $a 的实现类
  → 广播 com.redwind.magicorig.ACTION_ANC_SELECT (status)
  → [bluetooth 进程] RfcommController.safeHandleBroadcast
  → safeHandleUIEvent → setANCMode(status)
  → Enums.ANC_* → sendPacketSafe → SPP 0x4E 帧 → 耳机切换
```

### 2. 关键类/方法清单

| 层 | 类 / 方法 | 说明 |
|---|---|---|
| UI 行可用性 | `x7.a.r():Boolean` | **最终开关**，`displayPreference` 的 `0008 if-nez` 判定 |
| UI 控件 | `com.android.settings.preference.MultiStateSwitchingPanelPreference` | 三态开关；`onClick`/`Q(B)`/`R(a)` |
| 下发回调 | `$a.onMultiStateClicked(IB)V`（接口，不可 hook） | 实现类 **`NoiseControlPanelController`** |
| 实现类捕获 | hook `MultiStateSwitchingPanelPreference.R(a)` | 抓实例 → 对 `declaredMethods` 动态 hook |
| 跨进程桥 | 广播 `ACTION_ANC_SELECT` + extra `status` | Settings → bluetooth |
| 接收端 | `RfcommController.safeHandleBroadcast` | TAG = **`MagicOriG-Rfcomm`** |
| 下发 | `RfcommController.setANCMode(Int)` | → `sendPacketSafe` |

### 3. UI index → status → SPP 帧（最终映射）

| UI 按钮 | index | status | `AncMode` | SPP 帧 |
|---|---|---|---|---|
| **降噪** | 0 | **5** | **EXPERIMENT `0x10`** | `4E 05 00 00 01 02 10 00` |
| 透传 | 1 | 2 | TRANSPARENT `0x01` | `4E 05 00 00 01 02 01 00` |
| 关闭 | 2 | 1 | OFF `0x00` | `4E 05 00 00 01 02 00 00` |

> **用户要求「降噪」用实验性降噪** → `status=5` → `AncMode.EXPERIMENT(0x10)`（改动前是 `status=3 → 0x02 NORMAL`）。
> 实测日志：`→→ SPP 写出 len=8 hex=4E 05 00 00 01 02 10 00 socket=true`

### 4. 踩过的坑（排障顺序，务必按这个查）

1. **`setANCMode` 的 `if (mode == currentAnc) return` 早退** —— 与目标值相同就**不发包**。改为记录但仍下发。
2. **bluetooth 进程跑的是旧 APK** —— 装完必须 `svc bluetooth disable/enable` 重启进程，否则新代码不生效。
3. **grep 用错 TAG** —— `RfcommController` 是 **`MagicOriG-Rfcomm`**，我曾一直 grep `MagicOriG-BT` 导致恒为空、误导 4 轮。
4. **receiver 依赖 `connectPod`** —— A2DP 未回连时广播无人接收，表现为"广播 result=0 但无日志"。
5. **`onHook` 早于 `Application` 创建** —— `currentApplication` 为 null，需 `Handler(main).postDelayed` 延迟注册。
6. **`Log.d` 不进模块日志** —— `safeConnect` 里那条 `RFCOMM connect initiated` 是 debug 级，看不见；已改 `Log.i`。
7. **`screencap` 返回旧帧** —— 判据是状态栏时间；截图必须用新文件名并核对时间。
8. **`uiautomator dump` 要写 `/sdcard`**（`/data/local/tmp` 配 su 会 pull 失败）；`Get-Content` 必须 `-Encoding UTF8`。
9. **布局会变** —— 降噪按钮 y 在 22:29 是 1415、23:10 是 1920，**坐标不能写死**，应每次 dump 或直接主动调用 `onClick`。

### 5. 验证方法（以后照做）

- **不要**用日志判 UI（荣耀降噪路径不打 logcat）；用**选中态对比**截图。
- **不要**用 `topResumedActivity` 判断面板是否打开；用 `am start -W` 的 `Status: ok` + `dumpsys ... state=` + `finishing=false`。
- 时间窗口：`T = yyyy-MM-ddTHH:mm:ss`，只看 `≥ T` 的日志，才能把自己的 `input tap` 和用户手工点击区分开。

### 6. 操作约束

- **不重启手机**；只允许 `svc bluetooth disable/enable`（软开关）、`force-stop`、`setprop ctl.restart zygote`（软重启，已授权）。
- 上述坑 1–9 全部通过**软重启蓝牙/force-stop Settings** 解决，**全程未软重启 zygote、未重启手机**。

---

## 任务4：设备中心适配逆向（round 1–5，代码完成待验证）

> 用户指正：设备中心在 **`com.hihonor.nearby` / `com.hihonor.synergy`**，此前逆向的
> `DeviceControlCenter` 方向部分有效（卡片渲染在 controlcenter，数据在 nearby）。

### 1. APK 归属（aapt2 + 字节扫描实测）

| 包 | APK | 发现 |
|---|-----|------|
| `com.hihonor.nearby` | `/system/app/HnNearby/HnNearby.apk` (45MB) | 内嵌 **devicemanager 服务族**（`DeviceManagerService`/`BleDeviceManagerService`/`DeviceInfoService`）+ `message/EarphoneCfgChangeMessage`；dex 中文仅"耳机"×2（跨平台文案），**无降噪 UI** |
| `com.hihonor.synergy` | `/system/app/Synergy/Synergy.apk` | 纯协同服务（通知/消息共享），与耳机降噪无关 |
| `com.hihonor.controlcenter` | `/system/priv-app/DeviceControlCenter/DeviceControlCenter.apk` | **降噪卡渲染地**：`NoiseControl x12`、`Earphone x172` |

> 教训：**扫中文必须按 bytes 搜（UTF-8/UTF-16LE）或 UTF8 解码**，Latin1 解码搜中文恒为 0，会误判。

### 2. 降噪卡渲染链（controlcenter dex 反汇编）

```
DeviceServiceCardDataMgr.genDeviceServiceInfo(deviceId)
  ├ createEarphoneBatterySection(LinkDevice)
  ├ createEarphoneNoiseSection(LinkDevice)            ← 降噪卡创建
  └ DeviceServiceInfo{deviceId, deviceName, batteryLevel, serviceSections, isOnline}
DeviceServiceCardAdapter.bindServiceSections → addServiceSection(LinearLayout, ServiceSection)
  → EarphoneNoiseSectionController.addEarphoneNoiseSection(layout, section, str)
```

### 3. `createEarphoneNoiseSection` 关键结论（唯一退出 = LinkDevice null）

```java
data = EarphoneNoiseData.fromBatteryData(info.getBatteryData());   // DeviceBallManager 查
if (data == null) {                    // ← 我们的耳机走这里
    data = new EarphoneNoiseData();
    data.setSupportedModes(new ArrayList<>());   // 空 → 按钮全灰
    data.setCurrentMode(NoiseMode.UNKNOWN);
}
s.setTitle(R.string.earphone_noise_control_title); s.setSectionType(EARPHONE_NOISE);
return s;                               // 卡片照建，只是按钮不可点
```

### 4. 点击链与 4 个早退

```
EarphoneNoiseSectionController.onNoiseModeClick(NoiseMode)
  早退×4: showStatus==DISABLED / isSetting / isWearStateDisabledMode(mode) / 已是当前模式
  → highlightSingleMode(mode); isSetting = true
  → ncValue = EarphoneNoiseData.toNcModeValue(mode)
  → PropertyUtils.setNoiseCtrlMode(deviceId, ncValue, cb)     ★ 下发（static）
       cb: c2.s$b { onResult(String), onError(Exception), onResultWithKey(Map) }
  → DFXUtils.reportEarphoneModeAsync(...)
```

**`toNcModeValue` 映射**（反汇编 `{ordinal→value}` = `{0:1, 1:2, 2:0, 3:3, 4:-1}`）：
推定枚举序 → **`1=降噪(nc) 2=AWARENESS 0=OFF 3=ADAPTIVE -1=UNKNOWN`**

**NoiseMode 枚举常量**：`NOISE_CANCELLATION / AWARENESS / OFF / ADAPTIVE / UNKNOWN`

### 5. 设备列表总闸（"没添加进去"的根因）

```java
ControlCenterProvider.getDeviceList(): DccResult {
    dm = profile.getDeviceManager();
    if (dm == null || !dm.hasConnected()) return err;      // nearby 服务未就绪
    for (Device d : dm.getDevices())                       // ★ 唯一设备来源
        list.add(new LinkDevice(getDeviceUdid(d), name, type, nodeId, false, online, prodId));
    result.setExt(JsonUtils.sGson.toJson(list));
}
ControlCenterProvider.getLinkDeviceFromDeviceManager(udid) {
    dev = dm.getDeviceByUdid(udid);  if (dev == null) return null;   // ← 不在 devicemanager 即消失
}
```

**根因**：我们的耳机不在 `devicemanager.getDevices()` → 不进设备中心列表。

### 6. 注入所需数据（全部实测拿到）

| 项 | 值 |
|---|---|
| `LinkDevice` Gson 字段 | `deviceId / deviceName / hnDeviceType / deviceNodeId / macAddress / btMacAddress / prodId / isSelfDevice / hnOnLineTypeList` |
| 类型枚举 `HnDeviceProductType` | **`Third_EarPhone`**（第三方耳机）/ `EarPhone`（荣耀）/ Car, TV, Watch, Phone, Pad, PC, Printer… 共 16 |
| 我们设备 | `mac=18:5C:A1:52:10:36` `prodId=00VVD9` `name=原道 OriG in` |

### 7. 适配实现 `ControlCenterNoiseHook`（scope: `com.hihonor.controlcenter`）

| # | Hook | 作用 |
|---|------|------|
| 1 | `ControlCenterProvider.getDeviceList()` | ext JSON 数组 append 我们的 LinkDevice（**纯字符串**，已含则跳过、ext 异常不破坏）→ 耳机出现在列表 |
| 2 | `EarphoneNoiseData.fromBatteryData(BatteryData)` | 原返回 **null 才**注入 `[NOISE_CANCELLATION, AWARENESS, OFF]` + 按 `Settings.Global[magicorig_last_anc]` 恢复 currentMode；非 null 不干预（不影响荣耀自家耳机）→ 按钮点亮 |
| 3 | `PropertyUtils.setNoiseCtrlMode(String,int,cb)` | `nc1→readLastAnc()(用户默认档位5/4/3)`、`nc2→2`、`nc0→1` → 广播 `ACTION_ANC_SELECT` → **复用已打通 bluetooth 进程 SPP 0x4E 链路**；`proceed()` 保留原回调复位 isSetting |
| 4 | `onNoiseModeClick(NoiseMode)` | 观察点 |

接线：`HookEntry` 分支 / `scope.list` / `strings.xml` xposedscope 均加 `com.hihonor.controlcenter`。

### 8. 待设备验证（设备连续离线 5 轮）

```
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.hihonor.controlcenter
# 1) grep "ENTRY LOADED pkg=com.hihonor.controlcenter" —— 无则需 LSPosed 里重开模块
# 2) 打开设备中心截图：耳机是否出现 / 按钮是否点亮
# 3) 点降噪 → 看 "★ setNoiseCtrlMode(deviceId, nc=1)" → "→→ 已广播 ANC status=5"
#    → bluetooth 进程 SPP 写出 4E 05 00 00 01 02 <mode> 00
```





