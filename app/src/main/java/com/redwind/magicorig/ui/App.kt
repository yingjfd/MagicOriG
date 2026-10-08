package com.redwind.magicorig.ui

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redwind.magicorig.diag.ModuleDiagnostics

/**
 * 底部导航栏 — Material3 标准风格
 */
@Composable
private fun M3BottomBar(
    selected: Int,
    onSelect: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        for (i in 0..1) {
            val label = if (i == 0) "首页" else "关于"
            NavigationBarItem(
                selected = i == selected,
                onClick = { onSelect(i) },
                icon = {
                    Text(
                        if (i == 0) "⚡" else "ℹ️",
                        fontSize = 20.sp
                    )
                },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}

/**
 * 首页 — 电量 + 降噪档位 + 自检
 */
@Composable
private fun HomePage(context: Context) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "MagicOriG",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "为 NickHCK YuanDao OriG in 接入 MagicOS",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(4.dp))

        // ── 电量卡片 ──
        val batteryRaw = remember {
            try {
                android.provider.Settings.Global.getString(context.contentResolver, "magicorig_battery") ?: ""
            } catch (_: Throwable) { "" }
        }
        val leftPct = if (batteryRaw.contains("L")) {
            batteryRaw.substringAfter("L").substringBefore(",").toIntOrNull() ?: -1
        } else -1
        val rightPct = if (batteryRaw.contains("R")) {
            batteryRaw.substringAfterLast("R").toIntOrNull() ?: -1
        } else -1

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("耳机状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BatteryGauge("左耳", leftPct)
                    BatteryGauge("右耳", rightPct)
                }
            }
        }

        // ── 默认降噪档位（三档：实验性降噪 / 深度降噪 / 普通降噪）──
        val ancOptions = listOf("实验性降噪", "深度降噪", "普通降噪")
        val ancStatus = mapOf("实验性降噪" to "5", "深度降噪" to "4", "普通降噪" to "3")
        val currentAnc = remember {
            try {
                android.provider.Settings.Global.getString(context.contentResolver, "magicorig_last_anc") ?: "5"
            } catch (_: Throwable) { "5" }
        }
        var selectedAnc by remember(currentAnc) {
            mutableStateOf(ancOptions.find { ancStatus[it] == currentAnc } ?: "实验性降噪")
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("默认降噪档位", style = MaterialTheme.typography.titleMedium)
                Text(
                    "进入降噪页面时自动选中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                ancOptions.forEach { opt ->
                    val isSel = opt == selectedAnc
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedAnc = opt
                                ancStatus[opt]?.let { status ->
                                    runCatching {
                                        android.provider.Settings.Global.putString(
                                            context.contentResolver, "magicorig_last_anc", status
                                        )
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                selectedAnc = opt
                                ancStatus[opt]?.let { status ->
                                    runCatching {
                                        android.provider.Settings.Global.putString(
                                            context.contentResolver, "magicorig_last_anc", status
                                        )
                                    }
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(opt, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // ── 模块自检卡片 ──
        val diagReport = remember { runCatching { ModuleDiagnostics.collect(context) }.getOrNull() }
        val diagChecks = remember(diagReport) { diagReport?.checks() ?: emptyList() }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("模块识别自检", style = MaterialTheme.typography.titleMedium)
                if (diagReport == null) {
                    Text("读取失败", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        if (diagReport.recognized) "✓ LSPosed 可识别" else "✗ 不可识别",
                        color = if (diagReport.recognized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    diagChecks.forEach { check ->
                        val mark = if (check.ok == true) "✔" else if (check.ok == false) "✘" else "•"
                        val c = if (check.ok == true) MaterialTheme.colorScheme.primary
                                else if (check.ok == false) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        Text(
                            "$mark ${check.label}${check.detail?.let { " — $it" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 电量圆环
 */
@Composable
private fun BatteryGauge(label: String, pct: Int) {
    val color = when {
        pct < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        pct <= 20 -> MaterialTheme.colorScheme.error
        pct <= 50 -> MaterialTheme.colorScheme.tertiary   // 低电量用 M3 tertiary（warning 语义）
        else -> MaterialTheme.colorScheme.primary
    }
    val display = if (pct < 0) "?" else "$pct%"
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val stroke = 4.dp.toPx()
                drawCircle(
                    color = trackColor,
                    radius = size.minDimension / 2 - stroke / 2,
                    style = Stroke(width = stroke)
                )
                if (pct >= 0) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = (pct / 100f) * 360f,
                        useCenter = false,
                        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        size = Size(size.width - stroke, size.height - stroke),
                        topLeft = Offset(stroke / 2, stroke / 2)
                    )
                }
            }
            Text(display, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 关于页面 — 统一使用 M3 主题色
 */
@Composable
private fun AboutPage() {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "关于 MagicOriG",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("开源仓库", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "https://github.com/yingjfd/MagicOriG",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("引用项目", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BulletText("HyperOriG — 原始 HyperOS 适配版（核心 RFCOMM/电量/ANC 逻辑）")
                BulletText("LibXposed API — 现代 LSPosed 接口")
                BulletText("Material3 — UI 主题风格")
            }
        }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("技术栈", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BulletText("Kotlin / Jetpack Compose")
                BulletText("LSPosed / LibXposed (API 102)")
                BulletText("SPP RFCOMM · MBB 协议桥")
                BulletText("荣耀 MagicOS 11 (Android 17)")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "MIT License",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BulletText(text: String) {
    Text(
        "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 主入口
 */
@Composable
fun MagicOriGAppCompose() {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = { M3BottomBar(selected = tab, onSelect = { tab = it }) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomePage(context)
                1 -> AboutPage()
            }
        }
    }
}
