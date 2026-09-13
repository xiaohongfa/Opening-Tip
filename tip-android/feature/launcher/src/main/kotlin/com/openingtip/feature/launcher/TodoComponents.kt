package com.openingtip.feature.launcher

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openingtip.core.model.TodoItem
import com.openingtip.core.model.TodoType
import java.text.SimpleDateFormat
import java.util.*

/**
 * 完整待办与习惯打卡看板（支持常驻习惯+1/撤销与短期待办带截止时间）
 */
@Composable
fun TodoBoard(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier,
    onToggleTodo: (String, Boolean) -> Unit,
    onAddTodo: (String, TodoType, String?) -> Unit,
    onIncrementPermanent: (String) -> Unit,
    onUndoPermanent: (String) -> Unit,
    onDeleteTodo: (String) -> Unit,
    onClearCompletedShortTerm: () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showClearCompletedDialog by remember { mutableStateOf(false) }
    var selectedTodoFilter by remember { mutableIntStateOf(0) } // 0: 全部, 1: 常驻, 2: 短期

    val filteredTodos = remember(todos, selectedTodoFilter) {
        when (selectedTodoFilter) {
            1 -> todos.filter { it.type == TodoType.PERMANENT }
            2 -> todos.filter { it.type == TodoType.SHORT_TERM }
            else -> todos
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题与操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "自律打卡与待办",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val permanentCount = todos.count { it.type == TodoType.PERMANENT }
                    val shortTermCount = todos.count { it.type == TodoType.SHORT_TERM }
                    Text(
                        text = "常驻 $permanentCount · 短期 $shortTermCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (todos.any { it.type == TodoType.SHORT_TERM && it.isCompleted }) {
                        IconButton(onClick = { showClearCompletedDialog = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "清理已完成短期任务",
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    IconButton(onClick = { showAddDialog = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.AddCircle, contentDescription = "添加待办", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // 标签页筛选 (全部 / 常驻 / 短期)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTodoFilter == 0,
                    onClick = { selectedTodoFilter = 0 },
                    label = { Text("全部 (${todos.size})") }
                )
                FilterChip(
                    selected = selectedTodoFilter == 1,
                    onClick = { selectedTodoFilter = 1 },
                    label = { Text("常驻打卡") },
                    leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = selectedTodoFilter == 2,
                    onClick = { selectedTodoFilter = 2 },
                    label = { Text("短期待办") },
                    leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 待办条目列表
            if (filteredTodos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (selectedTodoFilter) {
                            1 -> "暂无常驻事项，点击右上角 + 添加习惯打卡（如自律、背单词）"
                            2 -> "暂无短期任务，点击右上角 + 添加"
                            else -> "暂无待办事项，点击右上角 + 添加"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filteredTodos.forEach { todo ->
                        key(todo.id) {
                            TodoRowItem(
                                todo = todo,
                                onToggle = { isChecked -> onToggleTodo(todo.id, isChecked) },
                                onIncrement = { onIncrementPermanent(todo.id) },
                                onUndo = { onUndoPermanent(todo.id) },
                                onDelete = { onDeleteTodo(todo.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTodoDialog(
            initialType = if (selectedTodoFilter == 2) TodoType.SHORT_TERM else TodoType.PERMANENT,
            onDismiss = { showAddDialog = false },
            onAdd = { title, type, targetTime ->
                onAddTodo(title, type, targetTime)
                showAddDialog = false
            }
        )
    }

    if (showClearCompletedDialog) {
        AlertDialog(
            onDismissRequest = { showClearCompletedDialog = false },
            icon = {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text("清理已完成待办", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("确定要清理所有已勾选完成的短期待办事项吗？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearCompletedDialog = false
                        onClearCompletedShortTerm()
                    }
                ) {
                    Text("确认清理")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCompletedDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun TodoRowItem(
    todo: TodoItem,
    onToggle: (Boolean) -> Unit,
    onIncrement: () -> Unit,
    onUndo: () -> Unit,
    onDelete: () -> Unit
) {
    var isHeatmapExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val todayKey = remember { dayFormat.format(Date()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (todo.type == TodoType.PERMANENT) {
                val timestamps = remember(todo.completionRecordsJson) { todo.getCompletionTimestamps() }
                val todayCount = remember(timestamps) {
                    timestamps.count { dayFormat.format(Date(it)) == todayKey }
                }

                // 第一行：标题 + 次数徽章 (左) ；撤销 + 打卡 + 删除 (右)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = todo.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SuggestionChip(
                            onClick = { isHeatmapExpanded = !isHeatmapExpanded },
                            label = { Text("已做 ${todo.completedCount} 次", fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = if (todo.completedCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            border = null,
                            modifier = Modifier.height(24.dp)
                        )
                    }

                    // 右侧操作按钮组（撤销、打卡、删除）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (todo.completedCount > 0) {
                            FilledTonalButton(
                                onClick = onUndo,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤销", modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("撤销", fontSize = 11.sp)
                            }
                        }

                        FilledTonalButton(
                            onClick = onIncrement,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("打卡", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        IconButton(onClick = { showDeleteConfirmDialog = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 第二行：今日状态 (左) ；热力图展开/收起 (右)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { isHeatmapExpanded = !isHeatmapExpanded }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (todayCount > 0) {
                        val lastTime = timestamps.lastOrNull()?.let { timeFormat.format(Date(it)) } ?: ""
                        Text(
                            text = "今日已打卡 $todayCount 次 · 最近 $lastTime",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Text(
                            text = "今日尚未打卡",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    Text(
                        text = if (isHeatmapExpanded) "收起热力图 ▲" else "📊 热力图 ▼",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 展开的热力图
                AnimatedVisibility(visible = isHeatmapExpanded) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        HabitHeatmap(todo = todo)
                    }
                }
            } else {
                // 短期任务模式
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Checkbox(
                            checked = todo.isCompleted,
                            onCheckedChange = onToggle,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = todo.title,
                                style = MaterialTheme.typography.bodyMedium,
                                textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (todo.isCompleted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                            )
                            if (todo.targetTime != null) {
                                Text(
                                    text = "⏰ ${todo.targetTime}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    IconButton(onClick = { showDeleteConfirmDialog = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = if (todo.type == TodoType.PERMANENT) "删除常驻习惯" else "删除待办事项",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (todo.type == TodoType.PERMANENT) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("确定要删除常驻习惯「${todo.title}」吗？")
                        if (todo.completedCount > 0) {
                            Text(
                                "该习惯已累计打卡 ${todo.completedCount} 次，删除后历史打卡记录与热力图将一并清除且不可恢复，请谨慎操作！",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                "删除后无法撤销。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    Text("确定要删除短期待办「${todo.title}」吗？")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun AddTodoDialog(
    initialType: TodoType,
    onDismiss: () -> Unit,
    onAdd: (String, TodoType, String?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var targetTime by remember { mutableStateOf("") }
    var selectedType by remember(initialType) { mutableStateOf(initialType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自律待办事项") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("事项内容 (如 自律、背单词、健身)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = targetTime,
                    onValueChange = { targetTime = it },
                    label = { Text("目标/计划时间 (可选)") },
                    singleLine = true,
                    placeholder = { Text("例如 21:30") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 时间快捷快捷词
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("今日内", "20:00", "22:00", "明日").forEach { preset ->
                        SuggestionChip(
                            onClick = { targetTime = preset },
                            label = { Text(preset, fontSize = 11.sp) },
                            modifier = Modifier.height(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("模式类型：", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedType = TodoType.PERMANENT }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedType == TodoType.PERMANENT,
                        onClick = { selectedType = TodoType.PERMANENT }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("常驻模式 (计数打卡/习惯养成)", fontWeight = FontWeight.Bold)
                        Text(
                            "长期保留，支持多次打卡、记录时间戳与撤销操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedType = TodoType.SHORT_TERM }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedType == TodoType.SHORT_TERM,
                        onClick = { selectedType = TodoType.SHORT_TERM }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("短期模式 (一次性任务)", fontWeight = FontWeight.Bold)
                        Text(
                            "带有截止时间，完成后打勾显示删除线，支持一键清理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onAdd(title.trim(), selectedType, targetTime.trim().ifBlank { null })
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("保存事项")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
