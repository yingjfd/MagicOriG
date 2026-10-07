# MagicOriG 防炸机说明

## 为什么 SystemUI hook 危险

SystemUI 是 Android 最重要的系统进程，负责状态栏、通知栏、锁屏等。
一个未捕获的异常就能让 SystemUI 崩溃 → 整个手机 UI 冻结/重启。

## 已实施的保护措施

### SystemUIHeadsetIconHook.kt
- `CentralSurfacesImpl.start` hook 内所有反射用 `runCatching`
- BroadcastReceiver 注册失败不阻塞，自动降级到 EXPORTED 模式
- 蓝牙 adapter 用后台线程查询，不在主线程反射
- 图标状态用 `stateGeneration` 防过期回调覆盖

### HeadsetStateDispatcher.kt  
- `mContext` 获取用 try-catch，失败则跳过
- RFCOMM 连接在独立子线程执行，不阻塞蓝牙服务线程
- 并发连接用 `AtomicBoolean` 互斥
- 备用 hook 路径（`tryAlternativeHook`），主路径失败后自动降级

### BluetoothUpstreamHeadsetHook.kt
- 每个 hook install 独立 try-catch，单个失败不影响其他
- callback 注册用 `AtomicBoolean isUpdatingState` 防并发
- handler.post 前有 looper 存活检查
- broadcast 发送用 `setPackage()` 限定目标包

### RfcommController.kt
- RFCOMM connect timeout 30s，防止无限等待卡死线程
- packet reader buffer 有长度上限，防止 OOM
- 所有广播发送用 `safeSendBroadcast` 包裹
- 断开连接时先取消协程再关闭 socket

### HookContext.kt
- `hookAfter` / `hookBefore` 外层用 `runCatching`
- hook 异常时返回原方法结果，不传播到宿主进程
- 兜底返回 `chain.proceed()` 确保原逻辑不受影响

## 调试命令

```bash
# 监听所有 MagicOriG 日志
adb logcat -s "MagicOriG-*"

# 查看 SystemUI 是否崩溃（爆炸信号）
adb logcat -s "AndroidRuntime:E" | grep -i systemui

# 查看蓝牙服务崩溃
adb logcat -s "AndroidRuntime:E" | grep -i bluetooth
```

如果 logcat 中出现 `FATAL EXCEPTION` 且包名为 `com.android.systemui` 或 `com.android.bluetooth`，说明保护不足，需调整。
