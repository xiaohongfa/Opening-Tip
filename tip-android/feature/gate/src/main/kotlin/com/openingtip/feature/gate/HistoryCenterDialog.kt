package com.openingtip.feature.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openingtip.core.model.Session
import com.openingtip.core.model.TodoItem
import com.openingtip.core.model.TodoType
import java.text.SimpleDateFormat
import java.util.*

@Immutable
data class SessionHistoryItem(
    val id: String,
    val intentText: String?,
    val startWallMs: Long,
    val endWallMs: Long?,
    val durationMs: Long,
    val appSummaries: List<GateAppUsageItem> = emptyList()
)

/**
 * 历史自律足迹与数据中心弹窗
 *
 * 用户可在此查看：
 * 1. 每次打开手机声明的意图历史与对应应用使用情况；
 * 2. 习惯与待办事项的完成轨迹；
 * 3. 一键调用系统分享导出 JSON 与 Markdown 数据包。
 */
@Composable
fun HistoryCenterDialog(
    sessions: List<SessionHistoryItem>,
    todos: List<TodoItem>,
    onDismiss: () -> Unit,
    onExportData: () -> Unit,
    onClearHistory: (() -> Unit)? = null
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 意图足迹, 1: 习惯与待办
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val fullDateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // 1. 弹窗顶部标题栏与快捷操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "📜 历史自律足迹",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "开屏意图记录 · 习惯打卡明细 · 数据导出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 操作栏：导出数据按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onExportData,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导出全部个人数据", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (onClearHistory != null && sessions.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空历史", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 2. Tab 切换
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("意图与解锁 (${sessions.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("待办与打卡 (${todos.size})") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 3. Tab 内容展示
                when (selectedTab) {
                    0 -> {
                        // 意图与会话足迹列表（按日期分组）
                        if (sessions.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无开屏意图记录，解锁提交意图后将自动归档", color = MaterialTheme.colorScheme.outline)
                            }
                        } else {
                            val groupedSessions = remember(sessions) {
                                sessions.groupBy { dayFormat.format(Date(it.startWallMs)) }
                            }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                groupedSessions.forEach { (dateStr, list) ->
                                    item(key = "header_$dateStr") {
                                        Text(
                                            text = "📅 $dateStr (${list.size} 次进入)",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                        )
                                    }

                                    items(list, key = { it.id }) { s ->
                                        SessionHistoryCard(session = s, timeFormat = timeFormat)
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // 待办与习惯打卡归档
                        if (todos.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无待办事项记录", color = MaterialTheme.colorScheme.outline)
                            }
                        } else {
                            val habits = remember(todos) { todos.filter { it.type == TodoType.PERMANENT } }
                            val shortTodos = remember(todos) { todos.filter { it.type == TodoType.SHORT_TERM } }

                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (habits.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "🎯 常驻习惯打卡记录 (${habits.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    items(habits, key = { it.id }) { habit ->
                                        HabitHistoryCard(habit = habit, fullDateFormat = fullDateFormat)
                                    }
                                }

                                if (shortTodos.isNotEmpty()) {
                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "📝 短期待办事项归档 (${shortTodos.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    items(shortTodos, key = { it.id }) { item ->
                                        ShortTodoHistoryCard(todo = item, fullDateFormat = fullDateFormat)
                                    }
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
private fun SessionHistoryCard(
    session: SessionHistoryItem,
    timeFormat: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val startStr = timeFormat.format(Date(session.startWallMs))
            val endStr = session.endWallMs?.let { timeFormat.format(Date(it)) } ?: "进行中"
            val durSec = session.durationMs / 1000
            val durText = if (durSec < 60) "${durSec}秒" else "${durSec / 60}分${durSec % 60}秒"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$startStr ~ $endStr · $durText",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (session.endWallMs == null) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("活跃中", fontSize = 10.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.height(20.dp),
                        border = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "意图：${session.intentText ?: "未填写意图，仅使用白名单"}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // 显示应用使用明细
            if (session.appSummaries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    session.appSummaries.take(3).forEach { app ->
                        val appSec = app.durationMs / 1000
                        val appDurStr = if (appSec < 60) "${appSec}秒" else "${appSec / 60}分"
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "${app.label} $appDurStr",
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HabitHistoryCard(
    habit: TodoItem,
    fullDateFormat: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(habit.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text("已打卡 ${habit.completedCount} 次", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            val timestamps = habit.getCompletionTimestamps()
            if (timestamps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val recent5 = timestamps.takeLast(5).reversed()
                Text(
                    text = "最近打卡时间：\n" + recent5.joinToString("\n") { "• " + fullDateFormat.format(Date(it)) },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ShortTodoHistoryCard(
    todo: TodoItem,
    fullDateFormat: SimpleDateFormat
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val statusText = if (todo.isCompleted) {
                    val compStr = todo.completedAt?.let { fullDateFormat.format(Date(it)) } ?: ""
                    "✅ 已完成 ($compStr)"
                } else {
                    "⏳ 待完成 ${todo.targetTime?.let { "· 目标 $it" } ?: ""}"
                }
                Text(
                    text = statusText,
                    fontSize = 11.sp,
                    color = if (todo.isCompleted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
