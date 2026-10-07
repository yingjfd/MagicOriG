#!/system/bin/sh
# MagicOriG 一键重启作用域脚本
# 类似 HyperOriG 的 restart_scope.sh，但针对 MagicOS 进程名调整
#
# 使用方法：在模块设置中授予 Root 权限后执行

PIDS="com.android.bluetooth com.huawei.android.bluetooth com.android.systemui com.android.settings"

for PKG in $PIDS; do
    P=$(pidof "$PKG" 2>/dev/null || ps | grep "$PKG" | awk '{print $2}' | tr '\n' ' ')
    if [ -n "$P" ]; then
        kill -9 $P 2>/dev/null
        echo "killed $PKG: $P"
    else
        echo "not running: $PKG"
    fi
done

echo "MagicOriG scope restart done"
