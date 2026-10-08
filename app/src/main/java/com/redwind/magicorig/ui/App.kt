package com.redwind.magicorig.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redwind.magicorig.config.ConfigManager
import com.redwind.magicorig.diag.ModuleDiagnostics
import com.redwind.magicorig.utils.MagicOriGAction

/**
 * Glass bottom bar composable — 模仿液态玻璃底栏
 * 背景：半透明 + 模糊视觉 + 顶部细线阴影
 */
@Composable
private fun GlassBottomBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(68.dp),
        color = GlassNav,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0..1) {
                val label = if (i == 0) "首页" else "关于"
                val icon = if (i == 0) "⚡" else "ℹ️"
                val isSel = i == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(i) }
                        .padding(12.dp)
                ) {
                    Text(icon, fontSize = 22.sp)
                    Text(
                        label,
                        fontSize = 11.sp,
                        color = if (isSel) GlassBlue else GlassTextSecondary,
                        fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }
    }
    // 顶部玻璃反射线
    Divider(color = Color(0x22FFFFFF), thickness = 1.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
}

/**
 * 首页 — 电量信息 + 默认降噪档位切换
 */
@Composable
private fun HomePage(context: Context, prefs: android.content.SharedPreferences) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 标题区
        Text(
            "MagicOriG",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = GlassTextPrimary
        )
        Text(
            "为 NickHCK YuanDao OriG in 接入 MagicOS",
            fontSize = 14.sp,
            color = GlassTextSecondary
        )

        Spacer(Modifier.height(4.dp))

        // ── 电量卡片（从 Settings.Global 读取） ──
        val batteryRaw = remember {
            try {
                val contentResolver = context.contentResolver
                android.provider.Settings.Global.getString(contentResolver, "magicorig_battery") ?: ""
            } catch (_: Throwable) { "" }
        }
        val leftPct = if (batteryRaw.contains("L")) {
            batteryRaw.substringAfter("L").substringBefore(",").toIntOrNull() ?: -1
        } else -1
        val rightPct = if (batteryRaw.contains("R")) {
            batteryRaw.substringAfterLast("R").toIntOrNull() ?: -1
        } else -1

        GlassCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("耳机状态", fontSize = 16.sp, fontWeight = FontWeight.Medium)
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

        // ── 默认降噪档位 ──
        val ancOptions = listOf("实验性降噪", "透传", "关闭")
        val ancStatus = mapOf("实验性降噪" to "5", "透传" to "2", "关闭" to "1")
        val currentAnc = remember {
            try {
                val contentResolver = context.contentResolver
                android.provider.Settings.Global.getString(contentResolver, "magicorig_last_anc") ?: "5"
            } catch (_: Throwable) { "5" }
        }
        var selectedAnc by remember(currentAnc) { mutableStateOf(
            ancOptions.find { ancStatus[it] == currentAnc } ?: "实验性降噪"
        ) }

        GlassCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("默认降噪档位", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("进入降噪页面时自动选中", fontSize = 12.sp, color = GlassTextSecondary)
                Spacer(Modifier.height(12.dp))
                ancOptions.forEach { opt ->
                    val isSel = opt == selectedAnc
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedAnc = opt
                                // 写入 Settings.Global 供 hook 读取
                                runCatching {
                                    val status = ancStatus[opt] ?: return@runCatching
                                    android.provider.Settings.Global.putString(
                                        context.contentResolver, "magicorig_last_anc", status
                                    )
                                }
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSel,
                            onClick = {
                                selectedAnc = opt
                                runCatching {
                                    val status = ancStatus[opt] ?: return@runCatching
                                    android.provider.Settings.Global.putString(
                                        context.contentResolver, "magicorig_last_anc", status
                                    )
                                }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = GlassBlue)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(opt, fontSize = 15.sp)
                    }
                }
            }
        }

        // ── 模块自检卡片 ──
        val diagReport = remember { runCatching { ModuleDiagnostics.collect(context) }.getOrNull() }
        val diagChecks = remember(diagReport) { diagReport?.checks() ?: emptyList() }
        GlassCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("模块识别自检", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (diagReport == null) {
                    Text("读取失败", fontSize = 13.sp, color = GlassRed)
                } else {
                    Text(
                        if (diagReport.recognized) "✓ LSPosed 可识别" else "✗ 不可识别",
                        fontSize = 14.sp,
                        color = if (diagReport.recognized) GlassGreen else GlassRed
                    )
                    diagChecks.forEach { check ->
                        val mark = if (check.ok == true) "✔" else if (check.ok == false) "✘" else "•"
                        val c = if (check.ok == true) GlassGreen else if (check.ok == false) GlassRed else GlassTextSecondary
                        Text(
                            "$mark ${check.label}${check.detail?.let { " — $it" } ?: ""}",
                            fontSize = 12.sp, color = c
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 电池圆环指示器
 */
@Composable
private fun BatteryGauge(label: String, pct: Int) {
    val color = when {
        pct < 0 -> GlassTextSecondary
        pct <= 20 -> GlassRed
        pct <= 50 -> Color(0xFFFF9500)
        else -> GlassGreen
    }
    val display = if (pct < 0) "?" else "$pct%"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(56.dp)) {
                val stroke = 4.dp.toPx()
                drawCircle(
                    color = Color(0x22CCCCCC),
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
        Text(label, fontSize = 12.sp, color = GlassTextSecondary)
    }
}

/**
 * 玻璃卡片容器——圆角、半透、微阴影
 */
@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = GlassCard,
        shadowElevation = 2.dp,
        tonalElevation = 0.dp
    ) {
        content()
    }
}

/**
 * 关于页面
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
        Text("关于 MagicOriG", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GlassTextPrimary)

        GlassCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("开源仓库", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "https://github.com/yingjfd/MagicOriG",
                    fontSize = 14.sp,
                    color = GlassBlue
                )
            }
        }

        GlassCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("引用项目", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                BulletText("HyperOriG — 原始 HyperOS 适配版（核心 RFCOMM/电量/ANC 逻辑）")
                BulletText("LibXposed API — 现代 LSPosed 接口")
                BulletText("miuix — UI 风格参考")
                BulletText("Liquid Glass — 底栏玻璃效果灵感")
            }
        }

        GlassCard {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("技术栈", fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
            fontSize = 13.sp,
            color = GlassTextSecondary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BulletText(text: String) {
    Text("• $text", fontSize = 13.sp, color = GlassTextSecondary)
}

/**
 * 主入口 — 底部栏 + 页面容器
 */
@Composable
fun MagicOriGAppCompose() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("magicorig_settings", Context.MODE_PRIVATE)
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            GlassBottomBar(selected = tab, onSelect = { tab = it })
        },
        containerColor = GlassBg
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomePage(context, prefs)
                1 -> AboutPage()
            }
        }
    }
}
