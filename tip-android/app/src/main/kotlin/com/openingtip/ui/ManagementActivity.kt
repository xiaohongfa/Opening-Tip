package com.openingtip.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.openingtip.TipApplication
import com.openingtip.core.database.entity.TipControlEntity
import com.openingtip.core.database.entity.TodoItemEntity
import com.openingtip.core.database.entity.WhitelistEntryEntity
import com.openingtip.core.model.*
import com.openingtip.core.security.SecretManager
import com.openingtip.core.security.SecretStore
import com.openingtip.feature.onboarding.AppItem
import com.openingtip.feature.onboarding.OnboardingScreen
import com.openingtip.feature.settings.ManagementScreen
import com.openingtip.feature.settings.PermissionStatusItem
import com.openingtip.service.GateGuardService
import com.openingtip.service.TipAccessibilityService
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManagementActivity : ComponentActivity() {

    private val app by lazy { application as TipApplication }
    private val database by lazy { app.database }
    private val secretManager by lazy { SecretManager() }
    private val secretStore by lazy { SecretStore(this, secretManager) }

    // 全局响应式权限状态，在 onResume 时自动触发重检与 Compose 重组
    private val isUsageGrantedState = mutableStateOf(false)
    private val isOverlayGrantedState = mutableStateOf(false)
    private val permissionItemsState = mutableStateOf<List<PermissionStatusItem>>(emptyList())

    override fun onResume() {
        super.onResume()
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(true)
            }
        } catch (_: Exception) {}
        refreshPermissions()

        // 针对国产系统返回时 AppOps 状态异步同步延迟：多段延时重检 (200ms, 500ms, 1000ms)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(200)
            refreshPermissions()
            kotlinx.coroutines.delay(300)
            refreshPermissions()
            kotlinx.coroutines.delay(500)
            refreshPermissions()
        }

        // 确保只要已开启 Tip 模式，守护服务 100% 维持前台保活
        lifecycleScope.launch(Dispatchers.IO) {
            val control = database.tipControlDao().getControl()
            if (control != null && control.enabled && control.whitelistConfirmed) {
                GateGuardService.startService(this@ManagementActivity)
            }
        }

        // 规范 2.4 节：Gate / RESTRICTED 阶段恢复的管理 Activity 不得进入编辑页，统一路由返回 Gate
        lifecycleScope.launch(Dispatchers.IO) {
            val control = database.tipControlDao().getControl()
            if (control != null && control.enabled && control.state == SessionState.RESTRICTED.name) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ManagementActivity, "限制阶段中无法进入设置", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@ManagementActivity, GateActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    }
                    startActivity(intent)
                    finish()
                }
            }
        }
    }

    private fun refreshPermissions() {
        val usage = checkUsageStatsPermission()
        val overlay = checkOverlayPermission()
        val accessibility = checkAccessibilityPermission()
        val battery = checkBatteryOptimizationPermission()
        val notification = checkNotificationPermission()
        val miuiPopup = checkMiuiOp(10020)
        val miuiAutostart = checkMiuiOp(10004)

        isUsageGrantedState.value = usage
        isOverlayGrantedState.value = overlay

        val isXiaomi = Build.MANUFACTURER.contains("Xiaomi", ignoreCase = true) ||
                Build.BRAND.contains("Xiaomi", ignoreCase = true) ||
                Build.BRAND.contains("Redmi", ignoreCase = true)

        val items = mutableListOf<PermissionStatusItem>()

        items.add(
            PermissionStatusItem(
                id = "accessibility",
                title = "无障碍金钟罩守护 (系统免杀+0ms防切后台)",
                description = "👉 步骤：点击【去开启】-> 点击【已下载的应用】-> 开启【开屏Tip】。提供 0ms 手势截杀与后台秒弹",
                isGranted = accessibility,
                isVital = true,
                onFix = {
                    try {
                        Toast.makeText(this@ManagementActivity, "👉 请在系统页点击【已下载的应用】-> 开启【开屏Tip】", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (_: Exception) {
                        openAppDetailsSettings()
                    }
                }
            )
        )

        items.add(
            PermissionStatusItem(
                id = "usage",
                title = "使用情况访问权限 (应用识别与统计)",
                description = "用于识别前台应用、防逃逸与记录自律使用时长",
                isGranted = usage,
                isVital = true,
                onFix = {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            )
        )

        items.add(
            PermissionStatusItem(
                id = "overlay",
                title = "在其他应用上层显示 (悬浮门禁)",
                description = "允许开屏自律门禁在手机解锁瞬间覆盖全屏",
                isGranted = overlay,
                isVital = true,
                onFix = {
                    requestOverlayPermission()
                }
            )
        )

        if (isXiaomi || !miuiPopup) {
            items.add(
                PermissionStatusItem(
                    id = "miui_popup",
                    title = "后台弹出界面权限 (小米/HyperOS专属)",
                    description = "小米系统专属权限，开启后解锁即可秒弹门禁界面，杜绝黑屏或延迟",
                    isGranted = miuiPopup,
                    isVital = true,
                    onFix = {
                        openMiuiPermEditor()
                    }
                )
            )
        }

        items.add(
            PermissionStatusItem(
                id = "battery",
                title = "电池策略设为无限制 (防后台休眠)",
                description = "防止系统在锁屏待机时冻结或休眠自律守护进程",
                isGranted = battery,
                isVital = true,
                onFix = {
                    requestIgnoreBatteryOptimization()
                }
            )
        )

        if (isXiaomi || !miuiAutostart) {
            items.add(
                PermissionStatusItem(
                    id = "miui_autostart",
                    title = "应用自启动权限 (小米/HyperOS专属)",
                    description = "开机或重启后自动拉起守护服务，保障自律不中断",
                    isGranted = miuiAutostart,
                    isVital = false,
                    onFix = {
                        openAutoStartSettings()
                    }
                )
            )
        }

        items.add(
            PermissionStatusItem(
                id = "notification",
                title = "通知与前台服务权限",
                description = "维持后台守护常驻通知以及锁屏全屏穿透唤醒",
                isGranted = notification,
                isVital = false,
                onFix = {
                    openNotificationSettings()
                }
            )
        )

        permissionItemsState.value = items
        android.util.Log.i("ManagementActivity", "refreshPermissions: items=${items.map { "${it.id}:${it.isGranted}" }}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()

        setContent {
            val control by database.tipControlDao().observeControl().collectAsState(initial = null)
            val whitelistEntries by database.whitelistDao().observeWhitelist().collectAsState(initial = emptyList())
            val allSessions by database.sessionDao().observeAllSessions().collectAsState(initial = emptyList())
            val todoEntities by database.todoDao().observeAllTodos().collectAsState(initial = emptyList())

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

            var currentStep by remember { mutableIntStateOf(0) }
            var installedApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
            val isUsageGranted by isUsageGrantedState
            val isOverlayGranted by isOverlayGrantedState

            LaunchedEffect(Unit) {
                installedApps = queryInstalledApps()
                refreshPermissions()
            }

            val isConfirmed = control?.whitelistConfirmed == true

            // 自动侦测循环：当处于向导第 3 步（权限配置）时，每秒自动重检一次，用户授权返回无需任何手动操作即刻刷新！
            LaunchedEffect(currentStep, isConfirmed) {
                if (!isConfirmed && currentStep == 3) {
                    while (true) {
                        refreshPermissions()
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }

            if (!isConfirmed) {
                // 首次使用引导流程
                OnboardingScreen(
                    currentStep = currentStep,
                    availableApps = installedApps,
                    isUsageAccessGranted = isUsageGranted,
                    isOverlayPermissionGranted = isOverlayGranted,
                    isManagedMode = false,
                    onStepChange = { currentStep = it },
                    onAppToggle = { pkg ->
                        installedApps = installedApps.map {
                            if (it.packageName == pkg) it.copy(isSelected = !it.isSelected) else it
                        }
                    },
                    onConfirmEmptyWhitelist = {
                        // 显式确认空白名单
                    },
                    onSaveSecret = { secret ->
                        saveSecretCredential(secret)
                        true
                    },
                    onRequestUsagePermission = {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                    onRequestOverlayPermission = {
                        requestOverlayPermission()
                    },
                    onOpenAppDetails = {
                        openAppDetailsSettings()
                    },
                    onRefreshPermissions = {
                        refreshPermissions()
                        val uStr = if (isUsageGrantedState.value) "已授权" else "未授权"
                        val oStr = if (isOverlayGrantedState.value) "已授权" else "未授权"
                        Toast.makeText(
                            this@ManagementActivity,
                            "权限检测：使用情况[$uStr]，悬浮窗[$oStr]",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onEnableTipMode = {
                        enableTipModeFirstTime(installedApps.filter { it.isSelected })
                    }
                )
            } else {
                // 已完成首次设置：展示管理设置界面
                val domainSessions = allSessions.map {
                    Session(
                        id = it.id,
                        bootId = it.bootId,
                        startWallMs = it.startWallMs,
                        endWallMs = it.endWallMs,
                        status = SessionStatus.valueOf(it.status),
                        intentText = it.intentText,
                        durationMs = it.durationMs
                    )
                }

                val appsWithSelection = installedApps.map { app ->
                    app.copy(isSelected = whitelistEntries.any { it.packageName == app.packageName })
                }

                ManagementScreen(
                    isEnabled = control?.enabled == true,
                    allApps = appsWithSelection,
                    sessions = domainSessions,
                    todos = domainTodos,
                    permissionItems = permissionItemsState.value,
                    onRefreshPermissions = { refreshPermissions() },
                    onToggleTodo = { id, isCompleted -> handleToggleTodo(id, isCompleted) },
                    onAddTodo = { title, type, targetTime -> handleAddTodo(title, type, targetTime) },
                    onIncrementPermanent = { id -> handleIncrementPermanent(id) },
                    onUndoPermanent = { id -> handleUndoPermanent(id) },
                    onDeleteTodo = { id -> handleDeleteTodo(id) },
                    onClearCompletedShortTerm = { handleClearCompletedShortTerm() },
                    onBack = { finish() },
                    onSaveWhitelist = { newPackages ->
                        saveUpdatedWhitelist(newPackages, installedApps)
                    },
                    onClearHistory = {
                        clearHistory()
                    },
                    onExportData = {
                        lifecycleScope.launch {
                            com.openingtip.core.platform.DataExportManager.exportAndShare(this@ManagementActivity, database)
                        }
                    },
                    onEnableTipMode = {
                        enableTipModeExisting()
                    },
                    onDisarmTipMode = {
                        disarmTipMode()
                    },
                    onWipeAllData = {
                        wipeAllPersonalData()
                    }
                )
            }
        }
    }

    private fun checkUsageStatsPermission(): Boolean {
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            }
            if (mode == AppOpsManager.MODE_ALLOWED) {
                return true
            }

            // 检查 unsafeCheckOpRawNoThrow（部分国产定制系统 mode 转换机制存在差异）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rawMode = appOps.unsafeCheckOpRawNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
                if (rawMode == AppOpsManager.MODE_ALLOWED) {
                    return true
                }
            }

            // 实测兜底 1：查询过去 7 天的使用统计（杜绝 1 小时内无活跃导致空结果的误判）
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm?.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 7 * 24 * 3600 * 1000L, now)
            if (!stats.isNullOrEmpty()) {
                return true
            }

            // 实测兜底 2：queryEvents 事件流探测
            val events = usm?.queryEvents(now - 24 * 3600 * 1000L, now)
            if (events != null && events.hasNextEvent()) {
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("ManagementActivity", "checkUsageStatsPermission error", e)
        }
        return false
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                startActivity(intent)
            }
        }
    }

    private fun openAppDetailsSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "请在系统设置中找到「开屏Tip」并开启后台弹出与自启动权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAccessibilityPermission(): Boolean {
        return try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val expected = "$packageName/${TipAccessibilityService::class.java.canonicalName}"
            val expectedShort = "$packageName/.service.TipAccessibilityService"
            enabledServices.contains(expected) || enabledServices.contains(expectedShort)
        } catch (_: Exception) {
            false
        }
    }

    private fun checkBatteryOptimizationPermission(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun checkMiuiOp(op: Int): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(appOps, op, Process.myUid(), packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            true
        }
    }

    private fun openMiuiPermEditor() {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", packageName)
            }
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    private fun openAutoStartSettings() {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                startActivity(intent)
            } else {
                openAppDetailsSettings()
            }
        } catch (_: Exception) {
            openAppDetailsSettings()
        }
    }

    private fun queryInstalledApps(): List<AppItem> {
        return try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            resolveInfos.mapNotNull { resolveInfo ->
                try {
                    val actInfo = resolveInfo.activityInfo ?: return@mapNotNull null
                    val pkg = actInfo.packageName ?: return@mapNotNull null
                    if (pkg == packageName) return@mapNotNull null
                    val label = try {
                        resolveInfo.loadLabel(pm)?.toString() ?: pkg
                    } catch (_: Exception) {
                        pkg
                    }
                    AppItem(
                        packageName = pkg,
                        appName = label,
                        isSelected = false
                    )
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.packageName }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveSecretCredential(secret: String) {
        val credential = secretManager.createCredential(secret)
        secretStore.save(credential)
    }

    private fun enableTipModeFirstTime(selectedApps: List<AppItem>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val entries = selectedApps.map {
                WhitelistEntryEntity(
                    packageName = it.packageName,
                    labelCache = it.appName,
                    selectedAt = now,
                    available = true
                )
            }
            database.whitelistDao().replaceWhitelist(entries, 1L, now)

            val sessionId = UUID.randomUUID().toString()
            val session = com.openingtip.core.database.entity.SessionEntity(
                id = sessionId,
                bootId = "boot-$now",
                startWallMs = now,
                status = SessionStatus.OPEN.name
            )
            val segment = com.openingtip.core.database.entity.SessionSegmentEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                kind = SegmentKind.RESTRICTED.name,
                startWallMs = now,
                whitelistRevision = 1L
            )

            database.sessionDao().createSessionIfAbsent(session, segment)
            database.tipControlDao().upsertControl(
                TipControlEntity(
                    singletonId = 1,
                    onboardingStep = 6,
                    whitelistConfirmed = true,
                    enabled = true,
                    state = SessionState.RESTRICTED.name,
                    activeSessionId = sessionId
                )
            )

            // 启动前台守护服务
            GateGuardService.startService(this@ManagementActivity)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@ManagementActivity, "开屏自律门禁已就绪！锁屏解锁后将自动呈现", Toast.LENGTH_LONG).show()
                // 首次开启直接拉起一次 Gate 体验门禁
                val gateIntent = Intent(this@ManagementActivity, GateActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                startActivity(gateIntent)
                finish()
            }
        }
    }

    private fun saveUpdatedWhitelist(newPackages: List<String>, allApps: List<AppItem>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val entries = newPackages.map { pkg ->
                val label = allApps.find { it.packageName == pkg }?.appName ?: pkg
                WhitelistEntryEntity(
                    packageName = pkg,
                    labelCache = label,
                    selectedAt = now,
                    available = true
                )
            }
            val nextRevision = System.currentTimeMillis()
            database.whitelistDao().replaceWhitelist(entries, nextRevision, now)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ManagementActivity, "白名单已保存，将在下次限制生效", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            database.sessionDao().deleteClosedSessionsBefore(now)
            database.usageDao().deleteSlicesBefore(now)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ManagementActivity, "已清空已结束的历史记录", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableTipModeExisting() {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val sessionId = UUID.randomUUID().toString()
            val session = com.openingtip.core.database.entity.SessionEntity(
                id = sessionId,
                bootId = "boot-$now",
                startWallMs = now,
                status = SessionStatus.OPEN.name
            )
            val segment = com.openingtip.core.database.entity.SessionSegmentEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                kind = SegmentKind.RESTRICTED.name,
                startWallMs = now,
                whitelistRevision = 1L
            )
            database.sessionDao().createSessionIfAbsent(session, segment)
            database.tipControlDao().updateEnabled(true, SessionState.RESTRICTED.name)
            database.tipControlDao().updateActiveSessionId(sessionId)

            // 启动前台守护服务
            GateGuardService.startService(this@ManagementActivity)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@ManagementActivity, "自律门禁已重新开启", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun disarmTipMode() {
        lifecycleScope.launch(Dispatchers.IO) {
            val control = database.tipControlDao().getControl()
            val now = System.currentTimeMillis()
            control?.activeSessionId?.let { id ->
                database.sessionDao().closeSessionIfOpen(id, now, null, SessionEndReason.USER_MANUAL.name)
            }
            database.tipControlDao().updateEnabled(false, SessionState.DISARMED.name)
            database.tipControlDao().updateActiveSessionId(null)

            // 停止前台守护服务
            GateGuardService.stopService(this@ManagementActivity)

            withContext(Dispatchers.Main) {
                Toast.makeText(this@ManagementActivity, "Tip 模式已停用", Toast.LENGTH_SHORT).show()
            }
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

    private fun wipeAllPersonalData() {
        lifecycleScope.launch(Dispatchers.IO) {
            GateGuardService.stopService(this@ManagementActivity)
            database.clearAllTables()
            secretStore.clear()
            try {
                val exportDir = java.io.File(getExternalFilesDir(null), "exports")
                if (exportDir.exists()) {
                    exportDir.deleteRecursively()
                }
            } catch (_: Exception) {}
            database.tipControlDao().upsertControl(
                TipControlEntity(
                    singletonId = 1,
                    onboardingStep = 0,
                    whitelistConfirmed = false,
                    enabled = false,
                    state = SessionState.DISARMED.name,
                    activeSessionId = null
                )
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ManagementActivity, "已彻底抹除所有本地个人数据，应用已恢复初始状态", Toast.LENGTH_LONG).show()
                finishAffinity()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(true)
            }
        } catch (_: Exception) {}
    }
}
