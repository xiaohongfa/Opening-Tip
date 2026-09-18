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
    permissionItems: List<PermissionStatusItem> = emptyList(),
    onRefreshPermissions: () -> Unit = {},
    onToggleTodo: (String, Boolean) -> Unit = { _, _ -> },
    onAddTodo: (String, TodoType, String?) -> Unit = { _, _, _ -> },
    onIncrementPermanent: (String) -> Unit = {},
    onUndoPermanent: (String) -> Unit = {},
    onCheckInPermanentDate: (String, Long) -> Unit = { _, _ -> },
    onUndoPermanentDate: (String, String) -> Unit = { _, _ -> },
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

    val missingVitalPermissions = remember(permissionItems) {
        permissionItems.filter { it.isVital && !it.isGranted }
    }

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
            if (missingVitalPermissions.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ 检测到 ${missingVitalPermissions.size} 项关键防杀权限未开启！",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "可能导致切后台无法拦截或被系统清理误杀，请前往配置。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        FilledTonalButton(
                            onClick = { selectedTab = 3 },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("去体检", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

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
                            onClearCompletedShortTerm = onClearCompletedShortTerm,
                            onCheckInDate = onCheckInPermanentDate,
                            onUndoCheckInDate = onUndoPermanentDate
                        )
                    }
                }
                1 -> WhitelistEditorTab(allApps = allApps, onSaveWhitelist = onSaveWhitelist)
                2 -> HistoryTab(
                    sessions = sessions,
                    todos = todos,
                    onExportData = onExportData,
                    onClearClick = { showClearConfirm = true },
                    onCheckInPermanentDate = onCheckInPermanentDate,
                    onUndoPermanentDate = onUndoPermanentDate
                )
                3 -> SystemStatusTab(
                    isEnabled = isEnabled,
                    permissionItems = permissionItems,
                    onRefreshPermissions = onRefreshPermissions,
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
    onClearClick: () -> Unit,
    onCheckInPermanentDate: (String, Long) -> Unit = { _, _ -> },
    onUndoPermanentDate: (String, String) -> Unit = { _, _ -> }
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
                        HabitHeatmap(
                            todo = habit,
                            onCheckInDate = onCheckInPermanentDate,
                            onUndoCheckInDate = onUndoPermanentDate
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemStatusTab(
    isEnabled: Boolean,
    permissionItems: List<PermissionStatusItem> = emptyList(),
    onRefreshPermissions: () -> Unit = {},
    onEnableTipMode: () -> Unit,
    onDisarmTipMode: () -> Unit,
    onExportData: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onWipeAllData: () -> Unit = {}
) {
    var showPermissionWarningDialog by remember { mutableStateOf(false) }
    val missingVital = remember(permissionItems) {
        permissionItems.filter { it.isVital && !it.isGranted }
    }

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
                    Button(onClick = {
                        if (missingVital.isNotEmpty()) {
                            showPermissionWarningDialog = true
                        } else {
                            onEnableTipMode()
                        }
                    }) {
                        Text("开启 Tip 模式")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 🛡️ 权限体检与系统防杀中心卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (missingVital.isEmpty())
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "🛡️ 权限体检与系统防杀中心",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (missingVital.isEmpty()) "✓ 所有核心防杀与防逃逸权限已就绪 (真·杀不掉)" else "⚠️ 存在 ${missingVital.size} 项关键防杀权限未开启",
                            fontSize = 12.sp,
                            color = if (missingVital.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    FilledTonalButton(
                        onClick = onRefreshPermissions,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("重新体检", fontSize = 11.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (permissionItems.isEmpty()) {
                    Text("暂无权限检查项", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                } else {
                    permissionItems.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                    if (item.isVital) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            color = if (item.isGranted)
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "核心",
                                                fontSize = 10.sp,
                                                color = if (item.isGranted)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = item.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (item.isGranted) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "已就绪",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            } else {
                                Button(
                                    onClick = item.onFix,
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    colors = if (item.isVital)
                                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    else
                                        ButtonDefaults.buttonColors()
                                ) {
                                    Text("去开启", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showPermissionWarningDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionWarningDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("⚠️ 关键防杀权限未全部开启") },
                text = {
                    Column {
                        Text("以下关键权限尚未开启：", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        missingVital.forEach {
                            Text("• ${it.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "未开启无障碍金钟罩或后台弹出权限，手速过快可能可以切到桌面或多任务清理被强杀。\n\n建议前往体检中心配置，是否仍要强行开启？",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPermissionWarningDialog = false
                            onEnableTipMode()
                        }
                    ) {
                        Text("仍要强行开启")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionWarningDialog = false }) {
                        Text("去逐一开启")
                    }
                }
            )
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
