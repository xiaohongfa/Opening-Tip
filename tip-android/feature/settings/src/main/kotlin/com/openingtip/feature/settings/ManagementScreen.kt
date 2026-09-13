package com.openingtip.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openingtip.core.model.Session
import com.openingtip.core.model.TodoItem
import com.openingtip.core.model.TodoType
import com.openingtip.feature.launcher.HabitHeatmap
import com.openingtip.feature.onboarding.AppItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * 管理与设置界面（规范 2.4 节：仅在 FULL 或 DISARMED 时可进入）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementScreen(
    isEnabled: Boolean,
    allApps: List<AppItem>,
    sessions: List<Session>,
    todos: List<TodoItem> = emptyList(),
    onToggleTodo: (String, Boolean) -> Unit = { _, _ -> },
    onAddTodo: (String, TodoType, String?) -> Unit = { _, _, _ -> },
    onIncrementPermanent: (String) -> Unit = {},
    onUndoPermanent: (String) -> Unit = {},
    onDeleteTodo: (String) -> Unit = {},
    onClearCompletedShortTerm: () -> Unit = {},
    onBack: () -> Unit,
    onSaveWhitelist: (List<String>) -> Unit,
    onClearHistory: () -> Unit,
    onExportData: () -> Unit = {},
    onWipeAllData: () -> Unit = {},
    onEnableTipMode: () -> Unit,
    onDisarmTipMode: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tip 管理与自律设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = onExportData,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出数据", fontSize = 12.sp)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("习惯与待办") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("白名单编辑") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("历史会话") })
                Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("系统状态") })
            }

            when (selectedTab) {
                0 -> {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        com.openingtip.feature.launcher.TodoBoard(
                            todos = todos,
                            onToggleTodo = onToggleTodo,
                            onAddTodo = onAddTodo,
                            onIncrementPermanent = onIncrementPermanent,
                            onUndoPermanent = onUndoPermanent,
                            onDeleteTodo = onDeleteTodo,
                            onClearCompletedShortTerm = onClearCompletedShortTerm
                        )
                    }
                }
                1 -> WhitelistEditorTab(allApps = allApps, onSaveWhitelist = onSaveWhitelist)
                2 -> HistoryTab(
                    sessions = sessions,
                    todos = todos,
                    onExportData = onExportData,
                    onClearClick = { showClearConfirm = true }
                )
                3 -> SystemStatusTab(
                    isEnabled = isEnabled,
                    onEnableTipMode = onEnableTipMode,
                    onDisarmTipMode = onDisarmTipMode,
                    onExportData = onExportData,
                    onClearHistory = { showClearConfirm = true },
                    onWipeAllData = onWipeAllData
                )
            }
        }

        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                title = { Text("清空历史记录") },
                text = { Text("确定要清空全部已结束的历史会话吗？当前正在进行中的会话与配置不会受影响。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearConfirm = false
                            onClearHistory()
                        }
                    ) {
                        Text("确认清空", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun WhitelistEditorTab(
    allApps: List<AppItem>,
    onSaveWhitelist: (List<String>) -> Unit
) {
    val selectedPackages = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(allApps) {
        allApps.forEach { app ->
            selectedPackages[app.packageName] = app.isSelected
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "修改白名单软件",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "修改并保存后将立即生成新版本，在下一次限制阶段（下次锁屏并重新解锁）生效，不中断当前无限制模式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(allApps) { app ->
                val isChecked = selectedPackages[app.packageName] ?: false
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedPackages[app.packageName] = !isChecked }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { selectedPackages[app.packageName] = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                val chosen = selectedPackages.filter { it.value }.keys.toList()
                onSaveWhitelist(chosen)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存白名单配置")
        }
    }
}

@Composable
private fun HistoryTab(
    sessions: List<Session>,
    todos: List<TodoItem>,
    onExportData: () -> Unit,
    onClearClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    var historySubFilter by remember { mutableIntStateOf(0) } // 0: 意图会话, 1: 习惯打卡与热力图

    val habits = remember(todos) { todos.filter { it.type == TodoType.PERMANENT } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 顶部操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = onExportData,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("导出全部个人数据", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            if (sessions.isNotEmpty()) {
                IconButton(onClick = onClearClick) {
                    Icon(Icons.Default.Delete, contentDescription = "清空历史", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 子筛选标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = historySubFilter == 0,
                onClick = { historySubFilter = 0 },
                label = { Text("意图会话 (${sessions.size})") }
            )
            FilterChip(
                selected = historySubFilter == 1,
                onClick = { historySubFilter = 1 },
                label = { Text("习惯热力图 (${habits.size})") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (historySubFilter == 0) {
            // 意图历史列表
            if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无意图会话记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                val grouped = remember(sessions) {
                    sessions.groupBy { dayFormat.format(Date(it.startWallMs)) }
                }

                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grouped.forEach { (dateStr, daySessions) ->
                        item(key = "header_$dateStr") {
                            Text(
                                text = "📅 $dateStr (${daySessions.size} 次解锁)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }

                        items(daySessions, key = { it.id }) { session ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val startStr = timeFormat.format(Date(session.startWallMs))
                                    val endStr = session.endWallMs?.let { timeFormat.format(Date(it)) } ?: "进行中"
                                    val durSec = session.durationMs / 1000
                                    val durText = if (durSec < 60) "${durSec}秒" else "${durSec / 60}分${durSec % 60}秒"

                                    Text(
                                        text = "意图：${session.intentText ?: "未填写意图，仅使用白名单软件"}",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "$startStr ~ $endStr · 共 $durText",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // 习惯热力图列表
            if (habits.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无常驻打卡习惯，可在【习惯与待办】标签添加", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(habits, key = { it.id }) { habit ->
                        HabitHeatmap(todo = habit)
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemStatusTab(
    isEnabled: Boolean,
    onEnableTipMode: () -> Unit,
    onDisarmTipMode: () -> Unit,
    onExportData: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onWipeAllData: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前 Tip 状态：", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isEnabled) "✓ 正在运行中 (ACTIVE)" else "✗ 已关闭 (DISARMED)",
                    color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (isEnabled) {
                    OutlinedButton(
                        onClick = onDisarmTipMode,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("手动关闭 Tip 限制")
                    }
                } else {
                    Button(onClick = onEnableTipMode) {
                        Text("开启 Tip 模式")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val context = androidx.compose.ui.platform.LocalContext.current
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🛡️ 小米/HyperOS/国产系统防杀与保活配置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "针对小米 HyperOS/MIUI 等系统的后台冻结机制：\n1. 【已默认防误杀】：本应用已从系统多任务列表中隐藏，您在多任务界面一键清理后台时绝不会误杀自律服务；\n2. 【后台弹出与无限制】：请点击下方按钮开启「后台弹出界面」并将省电策略设为「无限制」，确保在其他应用中锁屏后再解锁百分之百秒弹门禁。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                                    putExtra("extra_pkgname", context.packageName)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("后台弹出/锁屏显示", fontSize = 11.sp)
                    }
                    FilledTonalButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("电池设为无限制", fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            val intent = android.content.Intent().apply {
                                component = android.content.ComponentName(
                                    "com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                                )
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("前往设置自启动", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        var showWipeConfirm by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🗑️ 个人数据主权与彻底抹除", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "本软件所有意图记录、习惯打卡、应用使用时长均 100% 仅保存在手机本机私有存储中，绝不上云。\n您可以随时导出备份，或彻底抹除所有个人数据：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onExportData,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("导出全部数据", fontSize = 12.sp)
                    }
                    FilledTonalButton(
                        onClick = onClearHistory,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清空历史记录", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showWipeConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("彻底抹除全部个人数据并恢复出厂", fontSize = 12.sp)
                }
            }
        }

        if (showWipeConfirm) {
            AlertDialog(
                onDismissRequest = { showWipeConfirm = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("⚠️ 警告：彻底抹除全部个人数据") },
                text = {
                    Text("此操作将永久清空本地所有会话意图历史、习惯打卡热力图、待办清单、白名单配置以及安全暗号，将应用彻底恢复至初次安装的空状态！\n\n此操作不可逆，是否确定抹除？")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showWipeConfirm = false
                            onWipeAllData()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("确认彻底抹除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("💡 自律与白名单说明", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "本软件完全离线运行，不内置天气或冗余组件。如需使用天气、日程、音乐等工具，可随时在【白名单编辑】中将其勾选加入白名单，即可在限制期间正常打开使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ℹ️ 关于开屏 Tip & 开发者信息", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• 软件版本：v1.0.0 (纯血离线正式版)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("• 开发者：小红Fa", style = MaterialTheme.typography.bodyMedium)
                Text("• 研发组织：Hyperintell", style = MaterialTheme.typography.bodyMedium)
                Text("• 定位简介：开屏自律门禁工具，100% 离线运行，意图审视与习惯打卡。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "🛡️ 纯血离线与数据主权承诺：\n本应用未声明任何网络访问权限（INTERNET），绝无任何云端上报或埋点分析。所有历史意图记录、习惯打卡热力图、白名单配置均纯物理保存在您手机本机 SQLite 数据库中，数据完全归您所有，可随时导出或抹除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
