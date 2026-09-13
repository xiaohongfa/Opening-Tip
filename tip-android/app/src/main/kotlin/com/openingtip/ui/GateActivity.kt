package com.openingtip.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.openingtip.TipApplication
import com.openingtip.core.database.entity.SessionSegmentEntity
import com.openingtip.core.database.entity.TodoItemEntity
import com.openingtip.core.model.*
import com.openingtip.core.security.SecretManager
import com.openingtip.core.security.SecretStore
import com.openingtip.core.platform.DataExportManager
import com.openingtip.feature.gate.GateAppUsageItem
import com.openingtip.feature.gate.GateSessionSummary
import com.openingtip.feature.gate.GateScreen
import com.openingtip.feature.gate.SessionHistoryItem
import com.openingtip.feature.gate.WhitelistAppItem
import com.openingtip.service.GateGuardService
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏开屏自律门禁 Activity
 * 
 * 特性：
 * 1. 在用户完成系统指纹/密码解锁后浮现，覆盖在屏幕最上层；
 * 2. 拦截侧滑返回手势与防上滑逃逸（在未输入意图前保持前台）；
 * 3. 输入本次意图并点击“进入手机”后，执行 finish() 瞬间退场，无感直达原装桌面；
 * 4. 输入安全暗号后，关闭自律守护并 finish() 退出。
 */
class GateActivity : ComponentActivity() {

    private val app by lazy { application as TipApplication }
    private val database by lazy { app.database }
    private val secretManager by lazy { SecretManager() }
    private val secretStore by lazy { SecretStore(this, secretManager) }

    private var isLaunchingWhitelistApp = false
    @Volatile
    private var isIntentSubmitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isIntentSubmitted = false
        isLaunchingWhitelistApp = false
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(true)
            }
        } catch (_: Exception) {}
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(1002)
        } catch (_: Exception) {}

        // 窗口配置：保持屏幕常亮、全屏绘制背景、锁屏展示支持、禁用开窗动画（实现零延迟瞬间呈现）
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // 拦截侧滑返回手势，防止通过返回键直接逃离门禁
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 消费返回手势，保持门禁展示
                Log.d("GateActivity", "Back gesture intercepted")
            }
        })

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val control by database.tipControlDao().observeControl().collectAsState(initial = null)
                    val whitelistEntries by database.whitelistDao().observeWhitelist().collectAsState(initial = emptyList())
                    val todoEntities by database.todoDao().observeAllTodos().collectAsState(initial = emptyList())
                    val allSessions by database.sessionDao().observeAllSessions().collectAsState(initial = emptyList())
                    val allAppSummaries by database.usageDao().observeAllAppSummaries().collectAsState(initial = emptyList())

                    val startOfDayMs = remember {
                        Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    }
                    val todayUnlockCount by database.sessionDao().observeTodaySessionCount(startOfDayMs).collectAsState(initial = 0)
                    val todayUsageDurationMs by database.sessionDao().observeTodayTotalDuration(startOfDayMs).collectAsState(initial = 0L)

                    val historySessionItems = remember(allSessions, allAppSummaries) {
                        val summariesBySession = allAppSummaries.groupBy { it.sessionId }
                        allSessions.map { s ->
                            val appList = summariesBySession[s.id]?.map {
                                GateAppUsageItem(it.labelSnapshot ?: it.packageName, it.durationMs)
                            } ?: emptyList()
                            SessionHistoryItem(
                                id = s.id,
                                intentText = s.intentText,
                                startWallMs = s.startWallMs,
                                endWallMs = s.endWallMs,
                                durationMs = s.durationMs,
                                appSummaries = appList
                            )
                        }
                    }

                    val domainTodos = remember(todoEntities) {
                        todoEntities.map {
                            TodoItem(
                                id = it.id,
                                title = it.title,
                                type = TodoType.valueOf(it.type),
                                isCompleted = it.isCompleted,
                                createdAt = it.createdAt,
                                completedAt = it.completedAt,
                                targetTime = it.targetTime,
                                completedCount = it.completedCount,
                                completionRecordsJson = it.completionRecordsJson,
                                sortOrder = it.sortOrder
                            )
                        }
                    }

                    var isReconciling by remember { mutableStateOf(false) }
                    var previousReport by remember { mutableStateOf<GateSessionSummary?>(null) }

                    // 加载上次会话回顾统计
                    LaunchedEffect(control?.activeSessionId) {
                        isReconciling = true
                        withContext(Dispatchers.IO) {
                            try {
                                val prevSessionEntity = database.sessionDao().getBestPreviousClosedSession(control?.activeSessionId)
                                if (prevSessionEntity != null) {
                                    val segs = database.sessionDao().getSegmentsForSession(prevSessionEntity.id)
                                    val summaries = database.usageDao().getAppSummariesForSession(prevSessionEntity.id)
                                    val restrictedMs = segs.filter { it.kind == "RESTRICTED" }.sumOf { it.durationMs }
                                    val fullMs = segs.filter { it.kind == "FULL" }.sumOf { it.durationMs }

                                    val topUsage = summaries.take(3).map {
                                        GateAppUsageItem(it.labelSnapshot ?: it.packageName, it.durationMs)
                                    }
                                    val allUsage = summaries.map {
                                        GateAppUsageItem(it.labelSnapshot ?: it.packageName, it.durationMs)
                                    }

                                    previousReport = GateSessionSummary(
                                        intentText = prevSessionEntity.intentText,
                                        startWallMs = prevSessionEntity.startWallMs,
                                        endWallMs = prevSessionEntity.endWallMs,
                                        totalDurationMs = prevSessionEntity.durationMs,
                                        restrictedDurationMs = restrictedMs,
                                        fullDurationMs = fullMs,
                                        topApps = topUsage,
                                        allApps = allUsage,
                                        targetDurationMinutes = prevSessionEntity.targetDurationMinutes,
                                        isFirstUsage = false
                                    )
                                } else {
                                    previousReport = null
                                }
                            } catch (e: Exception) {
                                Log.e("GateActivity", "Error reconciling session: ${e.message}", e)
                            }
                        }
                        isReconciling = false
                    }

                    // 转换白名单项
                    val whitelistItems = remember(whitelistEntries) {
                        whitelistEntries.map {
                            WhitelistAppItem(it.packageName, it.labelCache ?: it.packageName)
                        }
                    }

                    GateScreen(
                        previousSessionReport = previousReport,
                        isReconciling = isReconciling,
                        whitelistApps = whitelistItems,
                        todos = domainTodos,
                        historySessions = historySessionItems,
                        todayUnlockCount = todayUnlockCount,
                        todayUsageDurationMs = todayUsageDurationMs,
                        onToggleTodo = { id, isCompleted -> handleToggleTodo(id, isCompleted) },
                        onAddTodo = { title, type, targetTime -> handleAddTodo(title, type, targetTime) },
                        onIncrementPermanent = { id -> handleIncrementPermanent(id) },
                        onUndoPermanent = { id -> handleUndoPermanent(id) },
                        onDeleteTodo = { id -> handleDeleteTodo(id) },
                        onClearCompletedShortTerm = { handleClearCompletedShortTerm() },
                        onExportData = {
                            lifecycleScope.launch {
                                DataExportManager.exportAndShare(this@GateActivity, database)
                            }
                        },
                        onClearHistory = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                database.sessionDao().deleteClosedSessionsBefore(now)
                                database.usageDao().deleteSlicesBefore(now)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@GateActivity, "已清空历史记录", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onSubmitIntentOrSecret = { input, targetMinutes ->
                            handleGateSubmission(input, targetMinutes, control?.activeSessionId)
                        },
                        onLaunchWhitelistApp = { pkg ->
                            isLaunchingWhitelistApp = true
                            launchAppPackage(pkg)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isIntentSubmitted = false
        isLaunchingWhitelistApp = false
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(1002)
        } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        isLaunchingWhitelistApp = false
        isIntentSubmitted = false
        isGateForeground = true
        hideSystemBars()
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(true)
            }
        } catch (_: Exception) {}
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(1002)
        } catch (_: Exception) {}

        // 仅当用户在系统设置中完全停用了 Tip 功能时退出，绝不因历史 FULL 状态错误自杀！
        lifecycleScope.launch(Dispatchers.IO) {
            val control = database.tipControlDao().getControl()
            if (control != null && !control.enabled) {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.post {
                val h = window.decorView.height
                val w = window.decorView.width
                if (h > 0 && w > 0) {
                    // 排除边缘系统导航手势响应（防止误触回桌面手势生效）
                    val bottomHeight = (h * 0.15f).toInt()
                    val rect = android.graphics.Rect(0, h - bottomHeight, w, h)
                    window.decorView.systemGestureExclusionRects = listOf(rect)
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户尝试底部上滑（回到桌面或切多任务），通知守护服务立即就绪
        if (!isIntentSubmitted && !isLaunchingWhitelistApp) {
            GateGuardService.instance?.forceLaunchGateActivity()
        }
    }

    override fun onPause() {
        super.onPause()
        isGateForeground = false
    }

    override fun onStop() {
        super.onStop()
        isGateForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isGateForeground = false
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(true)
            }
        } catch (_: Exception) {}
    }

    private fun handleGateSubmission(input: String, targetDurationMinutes: Int?, activeSessionId: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cred = secretStore.load()
            val isSecret = cred != null && secretManager.verify(input, cred)

            if (isSecret) {
                // 暗号关闭
                isIntentSubmitted = true
                GateGuardService.markSessionUnlocked()
                val now = System.currentTimeMillis()
                if (activeSessionId != null) {
                    database.sessionDao().closeSessionIfOpen(
                        sessionId = activeSessionId,
                        endWallMs = now,
                        endElapsedMs = null,
                        endReason = SessionEndReason.DISARMED_BY_SECRET.name
                    )
                }
                database.tipControlDao().updateEnabled(false, SessionState.DISARMED.name)
                database.tipControlDao().updateActiveSessionId(null)
                GateGuardService.stopService(this@GateActivity)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GateActivity, "暗号正确，Tip 模式已关闭", Toast.LENGTH_SHORT).show()
                    finish() // 门禁关闭，显现原装系统桌面
                }
            } else {
                // 提交本次意图进入 FULL 模式
                isIntentSubmitted = true
                GateGuardService.markSessionUnlocked()
                val now = System.currentTimeMillis()
                if (activeSessionId != null) {
                    val fullSeg = SessionSegmentEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = activeSessionId,
                        kind = SegmentKind.FULL.name,
                        startWallMs = now
                    )
                    database.sessionDao().switchToFull(
                        sessionId = activeSessionId,
                        currentSegmentId = "",
                        newSegment = fullSeg,
                        intentText = input.trim(),
                        targetDurationMinutes = targetDurationMinutes,
                        switchWallMs = now,
                        switchElapsedMs = null
                    )
                }
                database.tipControlDao().updateEnabled(true, SessionState.FULL.name)
                if (targetDurationMinutes != null && targetDurationMinutes > 0) {
                    GateGuardService.instance?.startFocusTimer(input.trim(), targetDurationMinutes)
                }
                withContext(Dispatchers.Main) {
                    val durationHint = targetDurationMinutes?.let { " (预计 ${it}分钟)" } ?: ""
                    Toast.makeText(this@GateActivity, "已声明意图$durationHint，请专注使用", Toast.LENGTH_SHORT).show()
                    finish() // 核心：门禁退出，无感显现原装系统桌面与手势！
                }
            }
        }
    }

    private fun launchAppPackage(pkg: String) {
        try {
            isLaunchingWhitelistApp = true
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "无法启动应用: $pkg", Toast.LENGTH_SHORT).show()
                isLaunchingWhitelistApp = false
            }
        } catch (e: Exception) {
            Toast.makeText(this, "启动异常: ${e.message}", Toast.LENGTH_SHORT).show()
            isLaunchingWhitelistApp = false
        }
    }

    private fun handleToggleTodo(id: String, isCompleted: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = if (isCompleted) System.currentTimeMillis() else null
            database.todoDao().toggleTodo(id, isCompleted, now)
        }
    }

    private fun handleAddTodo(title: String, type: TodoType, targetTime: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val entity = TodoItemEntity(
                id = UUID.randomUUID().toString(),
                title = title,
                type = type.name,
                targetTime = targetTime,
                createdAt = System.currentTimeMillis()
            )
            database.todoDao().insertTodo(entity)
        }
    }

    private fun handleIncrementPermanent(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val item = database.todoDao().getTodoById(id) ?: return@launch
            val domain = TodoItem(
                id = item.id,
                title = item.title,
                type = TodoType.valueOf(item.type),
                isCompleted = item.isCompleted,
                createdAt = item.createdAt,
                completedAt = item.completedAt,
                targetTime = item.targetTime,
                completedCount = item.completedCount,
                completionRecordsJson = item.completionRecordsJson,
                sortOrder = item.sortOrder
            )
            val now = System.currentTimeMillis()
            val (newCount, newJson) = domain.appendCompletion(now)
            database.todoDao().updatePermanentProgress(
                id = id,
                completedCount = newCount,
                completedAt = now,
                completionRecordsJson = newJson,
                isCompleted = true
            )
        }
    }

    private fun handleUndoPermanent(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val item = database.todoDao().getTodoById(id) ?: return@launch
            val domain = TodoItem(
                id = item.id,
                title = item.title,
                type = TodoType.valueOf(item.type),
                isCompleted = item.isCompleted,
                createdAt = item.createdAt,
                completedAt = item.completedAt,
                targetTime = item.targetTime,
                completedCount = item.completedCount,
                completionRecordsJson = item.completionRecordsJson,
                sortOrder = item.sortOrder
            )
            val (newCount, newJson) = domain.undoLastCompletion()
            val timestamps = domain.getCompletionTimestamps()
            val lastTime = if (timestamps.size > 1) timestamps[timestamps.size - 2] else null
            database.todoDao().updatePermanentProgress(
                id = id,
                completedCount = newCount,
                completedAt = lastTime,
                completionRecordsJson = newJson,
                isCompleted = newCount > 0
            )
        }
    }

    private fun handleDeleteTodo(id: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            database.todoDao().deleteTodo(id)
        }
    }

    private fun handleClearCompletedShortTerm() {
        lifecycleScope.launch(Dispatchers.IO) {
            database.todoDao().clearCompletedShortTermTodos()
        }
    }

    companion object {
        @Volatile
        var isGateForeground: Boolean = false
    }
}
