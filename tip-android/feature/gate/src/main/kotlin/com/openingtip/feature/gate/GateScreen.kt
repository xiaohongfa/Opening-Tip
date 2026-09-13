package com.openingtip.feature.gate

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import com.openingtip.core.model.TodoItem
import com.openingtip.core.model.TodoType
import com.openingtip.feature.launcher.TodoBoard
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@Immutable
data class GateAppUsageItem(
    val label: String,
    val durationMs: Long
)

@Immutable
data class GateSessionSummary(
    val intentText: String?,
    val startWallMs: Long,
    val endWallMs: Long?,
    val totalDurationMs: Long,
    val restrictedDurationMs: Long,
    val fullDurationMs: Long,
    val topApps: List<GateAppUsageItem>,
    val allApps: List<GateAppUsageItem>,
    val isFirstUsage: Boolean = false
)

data class WhitelistAppItem(
    val packageName: String,
    val appName: String
)

/**
 * Gate 主界面（严格遵循规范 2.2 节）
 * 全屏、无应用内退出按钮，固定四部分布局
 */
@Composable
fun GateScreen(
    previousSessionReport: GateSessionSummary?,
    isReconciling: Boolean,
    whitelistApps: List<WhitelistAppItem>,
    todos: List<TodoItem> = emptyList(),
    historySessions: List<SessionHistoryItem> = emptyList(),
    onToggleTodo: (String, Boolean) -> Unit = { _, _ -> },
    onAddTodo: (String, TodoType, String?) -> Unit = { _, _, _ -> },
    onIncrementPermanent: (String) -> Unit = {},
    onUndoPermanent: (String) -> Unit = {},
    onDeleteTodo: (String) -> Unit = {},
    onClearCompletedShortTerm: () -> Unit = {},
    onExportData: () -> Unit = {},
    onClearHistory: (() -> Unit)? = null,
    onSubmitIntentOrSecret: (String) -> Unit,
    onLaunchWhitelistApp: (String) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    var intentInput by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var isFolderExpanded by remember { mutableStateOf(false) }
    var isDetailsExpanded by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    // 严格按规范处理返回键消费链：收起键盘 -> 收起文件夹/详情 -> 停留在 Gate
    BackHandler(enabled = true) {
        if (showHistoryDialog) {
            showHistoryDialog = false
        } else if (isFolderExpanded) {
            isFolderExpanded = false
        } else if (isDetailsExpanded) {
            isDetailsExpanded = false
        } else {
            keyboardController?.hide()
            // 停留在 Gate，禁止退出到底层桌面
        }
    }

    val submitAction = {
        val raw = intentInput
        if (raw.isBlank()) {
            inputError = "请输入本次意图"
        } else {
            // 上层会先匹配暗号；若非暗号则作为意图保存并要求 trim 长度在 1~200
            val trimmed = raw.trim()
            if (trimmed.length > 200) {
                inputError = "意图长度不能超过 200 字"
            } else {
                keyboardController?.hide()
                inputError = null
                onSubmitIntentOrSecret(raw)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 顶部：时间与日期 + 历史足迹入口
            HeaderSection(onOpenHistory = { showHistoryDialog = true })

            // 2. 中部：上一次已结束会话回顾卡片
            PreviousSessionSection(
                report = previousSessionReport,
                isReconciling = isReconciling,
                isDetailsExpanded = isDetailsExpanded,
                onToggleDetails = { isDetailsExpanded = !isDetailsExpanded },
                onOpenHistory = { showHistoryDialog = true }
            )

            // 3. 习惯打卡与自律待办看板
            TodoBoard(
                todos = todos,
                onToggleTodo = onToggleTodo,
                onAddTodo = onAddTodo,
                onIncrementPermanent = onIncrementPermanent,
                onUndoPermanent = onUndoPermanent,
                onDeleteTodo = onDeleteTodo,
                onClearCompletedShortTerm = onClearCompletedShortTerm
            )

            // 4. 下部：“这次打开手机要做什么？”输入框与“进入手机”按钮
            IntentInputSection(
                input = intentInput,
                error = inputError,
                onInputChange = {
                    intentInput = it
                    inputError = null
                },
                onSubmit = submitAction
            )

            // 5. 底部：唯一“可用软件”文件夹入口
            WhitelistFolderSection(
                apps = whitelistApps,
                isExpanded = isFolderExpanded,
                onToggleExpand = { isFolderExpanded = !isFolderExpanded },
                onLaunchApp = onLaunchWhitelistApp
            )
        }

        if (showHistoryDialog) {
            HistoryCenterDialog(
                sessions = historySessions,
                todos = todos,
                onDismiss = { showHistoryDialog = false },
                onExportData = onExportData,
                onClearHistory = onClearHistory
            )
        }
    }
}

@Composable
private fun HeaderSection(
    onOpenHistory: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINESE) }
    val now = remember { Date() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = timeFormat.format(now),
                fontSize = 52.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = dateFormat.format(now),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FilledTonalButton(
            onClick = onOpenHistory,
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("历史足迹", fontSize = 12.sp)
        }
    }
}

@Composable
private fun PreviousSessionSection(
    report: GateSessionSummary?,
    isReconciling: Boolean,
    isDetailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "上次打开手机",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isReconciling) {
                        Text(
                            text = "正在整理...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(
                        onClick = onOpenHistory,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("全部历史 ➜", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (report == null || report.isFirstUsage) {
                Text(
                    text = "这是你的第一次使用 开屏 Tip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                val startTimeStr = timeFormat.format(Date(report.startWallMs))
                val endTimeStr = report.endWallMs?.let { timeFormat.format(Date(it)) } ?: "--:--"
                val intentDisplay = report.intentText ?: "未填写意图，仅使用可用软件"

                Text(
                    text = "意图：$intentDisplay",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$startTimeStr ~ $endTimeStr · 共 ${formatDuration(report.totalDurationMs)} (限制: ${formatDuration(report.restrictedDurationMs)}, 无限制: ${formatDuration(report.fullDurationMs)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("前台应用 Top 3：", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                val top3 = report.topApps.take(3)
                if (top3.isEmpty()) {
                    Text("无应用前台使用记录", style = MaterialTheme.typography.bodySmall)
                } else {
                    top3.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(app.label, style = MaterialTheme.typography.bodySmall)
                            Text(formatDuration(app.durationMs), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                if (report.allApps.size > 3) {
                    TextButton(onClick = onToggleDetails, modifier = Modifier.align(Alignment.End)) {
                        Text(if (isDetailsExpanded) "收起详情" else "展开完整使用明细")
                        Icon(
                            imageVector = if (isDetailsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = isDetailsExpanded) {
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            report.allApps.drop(3).forEach { app ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(app.label, style = MaterialTheme.typography.bodySmall)
                                    Text(formatDuration(app.durationMs), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntentInputSection(
    input: String,
    error: String?,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val quickPresets = remember {
        listOf("💬 回复微信", "📚 查阅资料", "🎯 专注工作", "📞 拨打电话", "📦 查看快递", "🏃 运动健身", "🛒 快速购物", "📝 随手记事")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 常用意图快捷气泡 (1-tap 选取)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickPresets.forEach { preset ->
                SuggestionChip(
                    onClick = { onInputChange(preset) },
                    label = { Text(preset, fontSize = 12.sp) },
                    shape = RoundedCornerShape(8.dp),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (input == preset) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    border = null,
                    modifier = Modifier.height(30.dp)
                )
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("这次打开手机要做什么？") },
            placeholder = { Text("点击上方快捷词或手动输入意图") },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false // 关闭第三方输入法联想
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("进入手机", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WhitelistFolderSection(
    apps: List<WhitelistAppItem>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedButton(
            onClick = onToggleExpand,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Icon(Icons.Default.Apps, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("可用软件 (${apps.size})")
        }

        AnimatedVisibility(visible = isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (apps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("当前未配置任何白名单软件", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(apps) { app ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onLaunchApp(app.packageName) }
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = app.appName.take(1),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0L)
    return if (seconds < 60) {
        "${seconds}秒"
    } else {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        if (remainingSeconds == 0L) "${minutes}分钟" else "${minutes}分${remainingSeconds}秒"
    }
}
