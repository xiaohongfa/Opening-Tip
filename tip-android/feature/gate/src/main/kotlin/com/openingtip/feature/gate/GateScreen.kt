package com.openingtip.feature.gate

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openingtip.core.model.TodoItem
import com.openingtip.core.model.TodoType
import com.openingtip.feature.launcher.TodoBoard
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    val targetDurationMinutes: Int? = null,
    val isFirstUsage: Boolean = false
)

data class WhitelistAppItem(
    val packageName: String,
    val appName: String
)

/**
 * Gate 主界面（开屏自律门禁核心界面）
 */
@Composable
fun GateScreen(
    previousSessionReport: GateSessionSummary?,
    isReconciling: Boolean,
    whitelistApps: List<WhitelistAppItem>,
    todos: List<TodoItem> = emptyList(),
    historySessions: List<SessionHistoryItem> = emptyList(),
    todayUnlockCount: Int = 0,
    todayUsageDurationMs: Long = 0L,
    onImportLocalAudio: (() -> Unit)? = null,
    onToggleTodo: (String, Boolean) -> Unit = { _, _ -> },
    onAddTodo: (String, TodoType, String?) -> Unit = { _, _, _ -> },
    onIncrementPermanent: (String) -> Unit = {},
    onUndoPermanent: (String) -> Unit = {},
    onDeleteTodo: (String) -> Unit = {},
    onClearCompletedShortTerm: () -> Unit = {},
    onCheckInPermanentDate: (id: String, timestamp: Long) -> Unit = { _, _ -> },
    onUndoPermanentDate: (id: String, dateKey: String) -> Unit = { _, _ -> },
    onExportData: () -> Unit = {},
    onClearHistory: (() -> Unit)? = null,
    onSubmitIntentOrSecret: (intent: String, targetDurationMinutes: Int?) -> Unit,
    onLaunchWhitelistApp: (String) -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var intentInput by remember { mutableStateOf("") }
    var selectedDurationMinutes by remember { mutableStateOf<Int?>(5) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var isFolderExpanded by remember { mutableStateOf(true) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showCountdownDialog by remember { mutableStateOf(false) }
    var showMusicDialog by remember { mutableStateOf(false) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileNameFromUri(context, uri)
            val track = TipMusicManager.importLocalAudio(context, uri, fileName)
            if (track != null) {
                Toast.makeText(context, "已导入音乐: ${track.title}", Toast.LENGTH_SHORT).show()
                TipMusicManager.playTrack(context, track)
            } else {
                Toast.makeText(context, "导入音频失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pinnedCountdown = remember(showCountdownDialog) {
        CountdownDaysManager.getPinnedItem(context)
    }

    // 消费返回手势：收起弹窗 -> 收起键盘 -> 停留在 Gate
    BackHandler(enabled = true) {
        if (showCountdownDialog) {
            showCountdownDialog = false
        } else if (showMusicDialog) {
            showMusicDialog = false
        } else if (showHistoryDialog) {
            showHistoryDialog = false
        } else {
            keyboardController?.hide()
        }
    }

    val submitAction = {
        val raw = intentInput
        if (raw.isBlank()) {
            inputError = "请输入本次意图"
        } else {
            val trimmed = raw.trim()
            if (trimmed.length > 200) {
                inputError = "意图长度不能超过 200 字"
            } else {
                keyboardController?.hide()
                inputError = null
                onSubmitIntentOrSecret(raw, selectedDurationMinutes)
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
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 顶部：时间与日期 + 今日自律数据看板 + 历史足迹入口 + 倒计日功能胶囊
            HeaderSection(
                todayUnlockCount = todayUnlockCount,
                todayUsageDurationMs = todayUsageDurationMs,
                pinnedCountdown = pinnedCountdown,
                onOpenHistory = { showHistoryDialog = true },
                onOpenCountdown = { showCountdownDialog = true }
            )

            // 2. 顶部快捷音乐播放条（播放/暂停、曲目名、切歌、曲库管理）
            QuickMusicBar(
                onOpenLibrary = { showMusicDialog = true }
            )

            // 3. 常用软件（置于首屏核心触及区，默认展开，展示应用图标，方便用户单手触达）
            WhitelistFolderSection(
                apps = whitelistApps,
                isExpanded = isFolderExpanded,
                onToggleExpand = { isFolderExpanded = !isFolderExpanded },
                onLaunchApp = onLaunchWhitelistApp
            )

            // 4. 核心交互：快捷意图（可自定义编辑） + 预计使用时间（含1分钟） + 意图输入与进入手机
            IntentInputSection(
                input = intentInput,
                error = inputError,
                selectedDurationMinutes = selectedDurationMinutes,
                onDurationChange = { selectedDurationMinutes = it },
                onInputChange = {
                    intentInput = it
                    inputError = null
                },
                onSubmit = submitAction
            )

            // 5. 上一次已结束会话回顾卡片（精炼意图与达成情况，界面不再记录前台应用）
            PreviousSessionSection(
                report = previousSessionReport,
                isReconciling = isReconciling,
                onOpenHistory = { showHistoryDialog = true }
            )

            // 6. 微习惯：深呼吸、喝水等温润日常提示
            MindfulHabitFooter()

            // 7. 习惯打卡与自律待办看板（移至末尾，避免进入手机和使用常用软件需要不必要的滑动）
            TodoBoard(
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

        if (showHistoryDialog) {
            HistoryCenterDialog(
                sessions = historySessions,
                todos = todos,
                onDismiss = { showHistoryDialog = false },
                onExportData = onExportData,
                onClearHistory = onClearHistory
            )
        }

        if (showCountdownDialog) {
            CountdownDialog(
                onDismiss = { showCountdownDialog = false }
            )
        }

        if (showMusicDialog) {
            MusicLibraryDialog(
                onDismiss = { showMusicDialog = false },
                onImportLocalAudio = {
                    onImportLocalAudio?.invoke() ?: audioPickerLauncher.launch("audio/*")
                }
            )
        }
    }
}

@Composable
private fun HeaderSection(
    todayUnlockCount: Int,
    todayUsageDurationMs: Long,
    pinnedCountdown: CountdownItem,
    onOpenHistory: () -> Unit,
    onOpenCountdown: () -> Unit
) {
    var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            currentTimeMs = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINESE) }
    val now = remember(currentTimeMs / 60000L) { Date(currentTimeMs) }

    val daysDiff = remember(pinnedCountdown) {
        CountdownDaysManager.calculateDaysDiff(pinnedCountdown.targetDate)
    }
    val daysText = remember(daysDiff) {
        CountdownDaysManager.formatDaysDisplay(daysDiff)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    fontSize = 15.sp,
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

        // 今日点亮次数与使用时长数据卡片 + 倒计日功能胶囊卡片
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📱", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("今日点亮", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$todayUnlockCount 次", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⏱️", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text("今日已用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatDurationShort(todayUsageDurationMs), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 倒计日功能卡片（点击打开倒计日列表与管理弹窗）
            Surface(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenCountdown() },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎯", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = pinnedCountdown.title,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = daysText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶部快捷音乐播放栏：
 * 快速启停离线专注音频或手机本地音乐，支持切歌与曲库管理
 */
@Composable
private fun QuickMusicBar(
    onOpenLibrary: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        TipMusicManager.initIfNeeded(context)
    }

    val isPlaying = TipMusicManager.isPlaying
    val currentTrack = TipMusicManager.currentTrack

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { TipMusicManager.togglePlay(context) },
        shape = RoundedCornerShape(14.dp),
        color = if (isPlaying)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { TipMusicManager.togglePlay(context) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isPlaying) "正在播放" else "快捷音乐",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isPlaying) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("🎵", fontSize = 10.sp)
                        }
                    }
                    Text(
                        text = currentTrack?.title ?: "点击播放专注白噪音/存好的音乐",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { TipMusicManager.playNext(context) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                FilledTonalIconButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = "曲库",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    if (result == null) {
        result = uri.path?.substringAfterLast('/')
    }
    return result ?: "本地音频_${System.currentTimeMillis()}"
}

@Composable
private fun PreviousSessionSection(
    report: GateSessionSummary?,
    isReconciling: Boolean,
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
                val intentDisplay = report.intentText ?: "未填写意图 (仅亮屏或使用可用软件)"

                Text(
                    text = "意图：$intentDisplay",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))

                // 主动研判上次意图是否达成（基于预计时间与真实进入桌面的使用时间对比）
                val actualMs = if (report.fullDurationMs > 0) report.fullDurationMs else report.totalDurationMs
                val actualDurationStr = formatDuration(actualMs)

                if (report.targetDurationMinutes != null && report.targetDurationMinutes > 0) {
                    val targetMs = report.targetDurationMinutes * 60 * 1000L
                    val isAchieved = actualMs <= targetMs
                    val diffMs = Math.abs(actualMs - targetMs)
                    val diffStr = formatDuration(diffMs)

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAchieved)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isAchieved)
                                    "🟢 按时达成 (预计${report.targetDurationMinutes}分钟 · 用时 $actualDurationStr · 提前 $diffStr 锁屏)"
                                else
                                    "🟠 超时使用 (预计${report.targetDurationMinutes}分钟 · 用时 $actualDurationStr · 超时 $diffStr)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isAchieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✓ 自律使用 · 持续 $actualDurationStr",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$startTimeStr ~ $endTimeStr · 专注 $actualDurationStr (总亮屏 ${formatDuration(report.totalDurationMs)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IntentInputSection(
    input: String,
    error: String?,
    selectedDurationMinutes: Int?,
    onDurationChange: (Int?) -> Unit,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(QuickIntentManager.getPresets(context)) }
    var showEditPresetsDialog by remember { mutableStateOf(false) }
    var newPresetText by remember { mutableStateOf("") }

    val durationOptions = remember {
        listOf(
            1 to "1分钟",
            3 to "3分钟",
            5 to "5分钟",
            10 to "10分钟",
            15 to "15分钟",
            30 to "30分钟",
            null to "不限"
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 可自定义编辑的快捷意图预设标签栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            presets.forEach { preset ->
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
            // 自定义/编辑按钮
            SuggestionChip(
                onClick = { showEditPresetsDialog = true },
                label = { Text("⚙️ 自定义", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) },
                shape = RoundedCornerShape(8.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ),
                border = null,
                modifier = Modifier.height(30.dp)
            )
        }

        // 预计使用时间选择器（含 1分钟、3分钟、5分钟、10分钟等）
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                text = "⏱️ 预计使用时间：",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                durationOptions.forEach { (minutes, label) ->
                    val isSelected = selectedDurationMinutes == minutes
                    FilterChip(
                        selected = isSelected,
                        onClick = { onDurationChange(minutes) },
                        label = { Text(label, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(28.dp)
                    )
                }
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
                autoCorrectEnabled = false
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
            val durationHint = selectedDurationMinutes?.let { " (${it}分钟)" } ?: ""
            Text("进入手机$durationHint", style = MaterialTheme.typography.titleMedium)
        }
    }

    // 自定义快捷意图对话框
    if (showEditPresetsDialog) {
        AlertDialog(
            onDismissRequest = { showEditPresetsDialog = false },
            title = { Text("⚙️ 自定义快捷意图标签") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text("点击 ✕ 删除不需要的标签，或在下方添加常用新意图：", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    presets.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            IconButton(
                                onClick = {
                                    val updated = presets.filter { it != item }
                                    presets = updated
                                    QuickIntentManager.savePresets(context, updated)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "删除", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("新增快捷意图：", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newPresetText,
                            onValueChange = { newPresetText = it },
                            placeholder = { Text("如: 背单词", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        FilledTonalButton(
                            onClick = {
                                val t = newPresetText.trim()
                                if (t.isNotEmpty() && !presets.contains(t)) {
                                    val updated = presets + t
                                    presets = updated
                                    QuickIntentManager.savePresets(context, updated)
                                    newPresetText = ""
                                }
                            }
                        ) {
                            Text("添加", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            QuickIntentManager.resetToDefaults(context)
                            presets = QuickIntentManager.getPresets(context)
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("恢复默认预设", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEditPresetsDialog = false }) {
                    Text("完成")
                }
            }
        )
    }
}

@Composable
private fun MindfulHabitFooter() {
    val habitTips = remember {
        listOf(
            "🌿 深吸一口气，想一想为什么拿起手机",
            "💧 喝口温水润润喉，稍作片刻放松",
            "👀 眨眨双眼，眺望远方让视线休息一下",
            "🧘 挺直后背，轻轻放松紧绷的肩膀",
            "🚶 办完这件正事，起身稍微走动活动一下",
            "🍵 慢下来，一次只专注当下这一件事"
        )
    }
    val currentTip = remember {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        habitTips[(day * 7 + hour) % habitTips.size]
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentTip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
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
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(Icons.Default.Apps, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("常用软件 (${apps.size})")
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
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
                        items(apps, key = { it.packageName }) { app ->
                            val context = LocalContext.current
                            val appIconBitmap = remember(app.packageName) {
                                try {
                                    val pm = context.packageManager
                                    val drawable = pm.getApplicationIcon(app.packageName)
                                    drawable.toBitmap(width = 120, height = 120, config = Bitmap.Config.ARGB_8888).asImageBitmap()
                                } catch (_: Exception) {
                                    null
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onLaunchApp(app.packageName) }
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (appIconBitmap != null) {
                                    Image(
                                        bitmap = appIconBitmap,
                                        contentDescription = app.appName,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
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

private fun formatDurationShort(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60
    return if (minutes < 60) {
        "${minutes}分钟"
    } else {
        val hours = minutes / 60
        val remMinutes = minutes % 60
        if (remMinutes == 0L) "${hours}小时" else "${hours}小时${remMinutes}分"
    }
}
