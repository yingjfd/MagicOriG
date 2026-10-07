package com.redwind.magicorig.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.redwind.magicorig.config.ConfigManager
import com.redwind.magicorig.diag.ModuleDiagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagicOriGAppCompose() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("magicorig_settings", Context.MODE_PRIVATE)
    var config by remember { mutableStateOf(ConfigManager.current()) }

    // 每次进入页面都重新自检，确保改完 APK 重装后能看到最新结果
    val diagReport = remember { runCatching { ModuleDiagnostics.collect(context) }.getOrNull() }
    val diagChecks = remember(diagReport) { diagReport?.checks() ?: emptyList() }

    val fakeDeviceId by remember { derivedStateOf { config.fakeDeviceId } }
    var inputId by remember { mutableStateOf(fakeDeviceId) }

    Scaffold(topBar = { TopAppBar(title = { Text("MagicOriG") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("MagicOriG — NickHCK YuanDao OriG in for MagicOS", style = MaterialTheme.typography.titleMedium)
            Text("为 OriG in 耳机接入 MagicOS 的 LSPosed 模块。连接后可在蓝牙设置中查看电量/ANC。", style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            Text("配置", style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = inputId,
                onValueChange = { inputId = it },
                label = { Text("伪造设备 ID") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    ConfigManager.updateFakeDeviceId(prefs, inputId)
                    config = ConfigManager.current()
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text("保存") }

            Spacer(modifier = Modifier.height(8.dp))
            ModuleStatusSection(diagReport, diagChecks)

            Spacer(modifier = Modifier.height(8.dp))
            Text("作用域说明：", style = MaterialTheme.typography.bodySmall)
            Text("- com.android.bluetooth（蓝牙服务）", style = MaterialTheme.typography.bodySmall)
            Text("- com.android.systemui（状态栏图标）", style = MaterialTheme.typography.bodySmall)
            Text("- com.android.settings（设置页面）", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(16.dp))
            Text("使用步骤：", style = MaterialTheme.typography.titleSmall)
            Text("1. 确保已安装 LSPosed（Zygisk 模式）", style = MaterialTheme.typography.bodySmall)
            Text("2. 在 LSPosed 中启用本模块并勾选上述作用域", style = MaterialTheme.typography.bodySmall)
            Text("3. 重启设备", style = MaterialTheme.typography.bodySmall)
            Text("4. 配对 OriG in 耳机（YUANDAO / OriG in / NiceHCK）", style = MaterialTheme.typography.bodySmall)
            Text("5. 在本 App 中查看/调整配置", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(24.dp))
            Text("参考：https://github.com/KiriChen-Wind/HyperOriG", style = MaterialTheme.typography.labelSmall)
            Text("基于 HyperOriG 移植至 MagicOS", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * 模块识别自检卡片。
 *
 * 结论口径与 LSPosed 管理器 ModuleUtil 完全一致，用来回答
 * 「为什么 LSPosed 模块列表里看不到这个模块」。
 */
@Composable
private fun ModuleStatusSection(
    report: ModuleDiagnostics.Report?,
    checks: List<ModuleDiagnostics.Check>
) {
    if (report == null) {
        Text("模块自检：读取失败", style = MaterialTheme.typography.titleSmall)
        Text("无法打开 APK，无法判断 LSPosed 识别状态。", style = MaterialTheme.typography.bodySmall)
        return
    }

    val verdict = if (report.recognized) "LSPosed 会把它识别为模块" else "LSPosed 不会把它当作模块（列表里看不到）"
    val verdictOk = report.recognized

    Text("模块识别自检", style = MaterialTheme.typography.titleSmall)
    Text(
        verdict,
        style = MaterialTheme.typography.bodyMedium,
        color = if (verdictOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    Text("APK：${report.apkPath}", style = MaterialTheme.typography.labelSmall)
    Text("识别路径：${if (report.modern) "modern (java_init.list)" else if (verdictOk) "legacy (xposedminversion)" else "无"}",
        style = MaterialTheme.typography.labelSmall)

    Spacer(modifier = Modifier.height(4.dp))

    checks.forEach { check ->
        val mark = when (check.ok) {
            true -> "✔"
            false -> "✘"
            null -> "•"
        }
        val color = when (check.ok) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            "$mark ${check.label}${check.detail?.let { " — $it" } ?: ""}",
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}
