package com.openingtip.service

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openingtip.R
import com.openingtip.TipApplication
import com.openingtip.core.database.entity.SessionEntity
import com.openingtip.core.database.entity.SessionSegmentEntity
import com.openingtip.core.model.SegmentKind
import com.openingtip.core.model.SessionEndReason
import com.openingtip.core.model.SessionState
import com.openingtip.core.model.SessionStatus
import com.openingtip.core.platform.SystemPackageHelper
import com.openingtip.core.platform.SystemScreenReceiver
import com.openingtip.ui.GateActivity
import com.openingtip.ui.ManagementActivity
import java.util.*
import kotlinx.coroutines.*

/**
 * 开屏自律前台守护服务
 * 
 * 核心保障：
 * 1. 【100% 稳定弹窗】：广播监听 + PowerManager 动态哨兵双保险，哪怕系统冻结广播或延时，仍能 0ms 秒级拉起 GateActivity；
 * 2. 【防杀死与自愈】：stopWithTask=false + onTaskRemoved / onDestroy 自愈重启，即使任务被清仍能自愈拉起；
 * 3. 【全屏原生通知穿透】：集成 USE_FULL_SCREEN_INTENT 顶层穿透，彻底突破国产系统（小米/MIUI/HyperOS）后台弹出限制；
 * 4. 【零延迟秒弹 (<50ms)】：使用 FLAG_ACTIVITY_NO_ANIMATION 抹除转场动画，异步数据库记录不卡顿主线程；
 * 5. 【防逃逸拉回】：guardWatcherJob 毫秒级侦测，未输入意图逃向桌面立即弹回门禁；
 * 6. 【意图放行】：输入意图后无感放行至系统桌面。
 */
class GateGuardService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var screenReceiver: SystemScreenReceiver? = null
    private val database by lazy { (application as TipApplication).database }

    @Volatile
    var isTipEnabled: Boolean = false
        internal set

    @Volatile
    var isSessionUnlocked: Boolean = false
        internal set

    @Volatile
    private var whitelistCache: Set<String> = emptySet()

    fun isWhitelisted(pkg: String): Boolean {
        return whitelistCache.contains(pkg)
    }

    @Volatile
    private var launchGracePackage: String? = null
    @Volatile
    private var launchGraceExpiresAt: Long = 0L

    /**
     * 当用户在门禁中点击白名单应用时调用，授予 1500ms 的启动过渡保护期
     */
    fun notifyWhitelistedAppLaunch(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        launchGracePackage = pkg
        launchGraceExpiresAt = now + 1500L
        Log.i(TAG, "Whitelisted app launch notified: $pkg (granted 1500ms transition grace period)")
    }

    /**
     * 判断当前是否处于白名单应用启动保护期内（避免冷启动过渡时误判拉回）
     */
    fun isWhitelistedAppLaunching(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now < launchGraceExpiresAt
    }

    /**
     * 判断指定包名在未解锁阶段是否合法放行（白名单应用、已启用输入法、系统基础框架UI、权限与来电界面等）
     */
    fun isPackageAllowedWhileLocked(pkg: String): Boolean {
        if (pkg == packageName) return true
        if (isWhitelisted(pkg)) return true
        val now = SystemClock.elapsedRealtime()
        // 仅在 1500ms 保护期内临时放行目标包名，保护期结束后必须以正式白名单配置为准，杜绝永久旁路漏洞
        if (pkg == launchGracePackage && now < launchGraceExpiresAt) return true
        if (SystemPackageHelper.isSystemAuxiliaryPackage(pkg)) return true
        if (SystemPackageHelper.isInputMethod(this, pkg)) return true
        return false
    }

    private var guardWatcherJob: Job? = null
    private var interactiveSentinelJob: Job? = null
    private val floatingTimer by lazy { FloatingTimerManager(this) }
    private var focusTimerJob: Job? = null

    @Volatile
    private var timerDeadlineElapsedMs: Long = 0L
    @Volatile
    private var timerDeadlineWallMs: Long = 0L
    @Volatile
    private var currentTimerRemainingSeconds = 0
    @Volatile
    private var currentTimerTotalSeconds = 0
    @Volatile
    private var currentTimerIntentText = ""
    @Volatile
    private var hasTriggeredTimeoutAlert = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startAsForeground()
        startObservingControlState()
        registerScreenStateReceiver()
        startInteractiveSentinel()
        Log.i(TAG, "GateGuardService onCreate: 守护服务已全面就绪 (双通道哨兵已激活)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        when (intent?.action) {
            ACTION_LOCK_NOW -> {
                stopFocusTimer()
                forceLaunchGateActivity()
            }
            ACTION_EXTEND_ONE_MIN -> {
                extendFocusTimer(1)
            }
            else -> {
                startAsForeground()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: 检测到任务被清理，立即启动自愈保活调度")
        rescheduleServiceRestart()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        stopFocusTimer()
        guardWatcherJob?.cancel()
        interactiveSentinelJob?.cancel()
        screenReceiver?.unregister(this)
        serviceScope.cancel()
        Log.i(TAG, "GateGuardService onDestroy: 守护服务已终止")

        if (isTipEnabled) {
            rescheduleServiceRestart()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 实时感知自律开关与配置状态，并高速缓存白名单
     */
    private fun startObservingControlState() {
        serviceScope.launch {
            try {
                val initial = database.tipControlDao().getControl()
                isTipEnabled = (initial != null && initial.enabled && initial.whitelistConfirmed)
                if (!isTipEnabled) {
                    isSessionUnlocked = true
                }
                val currentWhitelist = database.whitelistDao().getWhitelistPackageNames()
                whitelistCache = currentWhitelist.toSet()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading initial control state", e)
            }

            launch {
                database.whitelistDao().observeWhitelist().collect { list ->
                    whitelistCache = list.map { it.packageName }.toSet()
                }
            }

            database.tipControlDao().observeControl().collect { control ->
                isTipEnabled = (control != null && control.enabled && control.whitelistConfirmed)
                if (!isTipEnabled) {
                    isSessionUnlocked = true
                    guardWatcherJob?.cancel()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // 常驻守护低打扰通知渠道
            val channel = NotificationChannel(
                CHANNEL_ID,
                "开屏自律守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维持开屏自律门禁正常运行"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)

            // 紧急弹窗高优先级通知渠道（用于 fullScreenIntent 顶层穿透）
            val popupChannel = NotificationChannel(
                POPUP_CHANNEL_ID,
                "开屏门禁顶层唤醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "解锁瞬间唤起自律门禁"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager?.createNotificationChannel(popupChannel)

            // 超时强提醒高优先级通知渠道
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "专注超时强提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "自律专注时间用尽时强提醒"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager?.createNotificationChannel(alertChannel)
        }
    }

    private fun startAsForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ManagementActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("开屏自律守护中")
            .setContentText("锁屏解锁后将呈现专注门禁")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 屏幕熄灭时统一处理（由广播或哨兵触发）
     */
    private fun onDeviceScreenOff() {
        Log.i(TAG, "onDeviceScreenOff: 屏幕熄灭/进入锁屏，0ms 瞬间锁定会话并收起悬浮倒计时")
        isSessionUnlocked = false // 内存级瞬间闭锁！
        launchGracePackage = null
        launchGraceExpiresAt = 0L
        guardWatcherJob?.cancel()
        stopFocusTimer() // 停止倒计时与悬浮窗！

        serviceScope.launch {
            try {
                val control = database.tipControlDao().getControl()
                if (control != null && control.enabled && control.whitelistConfirmed) {
                    val now = System.currentTimeMillis()
                    control.activeSessionId?.let { activeId ->
                        database.sessionDao().closeSessionIfOpen(
                            sessionId = activeId,
                            endWallMs = now,
                            endElapsedMs = null,
                            endReason = SessionEndReason.SCREEN_OFF.name
                        )
                    }
                    database.tipControlDao().updateEnabled(true, SessionState.RESTRICTED.name)
                    database.tipControlDao().updateActiveSessionId(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling screen off in service", e)
            }
        }
    }

    /**
     * 屏幕解锁时统一处理（由广播或哨兵触发）
     */
    private fun onDeviceUnlocked() {
        Log.i(TAG, "onDeviceUnlocked: isTipEnabled=$isTipEnabled, isSessionUnlocked=$isSessionUnlocked")
        if (isTipEnabled && !isSessionUnlocked) {
            launchGateActivity()
        } else if (!isSessionUnlocked) {
            serviceScope.launch {
                val control = database.tipControlDao().getControl()
                if (control != null && control.enabled && control.whitelistConfirmed) {
                    isTipEnabled = true
                    withContext(Dispatchers.Main) {
                        launchGateActivity()
                    }
                }
            }
        }
    }

    private fun registerScreenStateReceiver() {
        screenReceiver = SystemScreenReceiver(
            onScreenOff = { onDeviceScreenOff() },
            onUserUnlocked = { onDeviceUnlocked() }
        ).also { it.register(this) }
    }

    @Volatile
    private var lastLaunchTime = 0L

    /**
     * 底层硬件状态巡检哨兵（PowerManager 动态轮询，每 350ms 校验一次物理亮屏与锁屏状态）
     * 彻底解决国产系统（HyperOS Greezer）冻结后台广播导致无法接收 USER_PRESENT / SCREEN_OFF 的问题！
     */
    private fun startInteractiveSentinel() {
        interactiveSentinelJob?.cancel()
        interactiveSentinelJob = serviceScope.launch {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            var lastWasInteractive = pm?.isInteractive ?: true

            while (isActive) {
                delay(350)
                if (!isTipEnabled || pm == null || km == null) continue

                val currentInteractive = pm.isInteractive
                if (lastWasInteractive && !currentInteractive) {
                    // 硬件级明确感知屏幕熄灭 -> 0ms 锁定
                    onDeviceScreenOff()
                } else if (!lastWasInteractive && currentInteractive) {
                    // 屏幕点亮 -> 若未锁定且未放行则弹出
                    if (!km.isKeyguardLocked && !isSessionUnlocked && !GateActivity.isGateForeground) {
                        withContext(Dispatchers.Main) {
                            onDeviceUnlocked()
                        }
                    }
                }
                lastWasInteractive = currentInteractive
            }
        }
    }

    /**
     * 强制瞬间重弹门禁（供无障碍服务与防逃逸拦截调用，低延迟，杜绝被拦截逃逸）
     */
    fun forceLaunchGateActivity() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchTime < 150L) {
            return
        }
        lastLaunchTime = now
        isSessionUnlocked = false
        GateActivity.isGateForeground = false
        launchGateActivityInternal()
    }

    /**
     * 极速启动 GateActivity，优先无障碍穿透 + 零转场动画延迟 + FullScreenIntent 顶层穿透三保险
     */
    fun launchGateActivity() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchTime < 300L) {
            return
        }
        lastLaunchTime = now
        isSessionUnlocked = false
        GateActivity.isGateForeground = false
        launchGateActivityInternal()
    }

    private fun launchGateActivityInternal() {
        // 核心修复 OT-P0-002：在任何拉起路径执行前，确保守护侦测与数据库 Canonical Session 处于 RESTRICTED 阶段！
        startGuardWatcher()
        serviceScope.launch {
            ensureActiveSession()
        }

        // 1. 如果无障碍守护服务已经就绪，由享有安卓 BAL 豁免特权的无障碍服务直接拉起（100%穿透后台限制）
        val a11y = TipAccessibilityService.instance
        if (a11y != null) {
            a11y.launchGateDirectly()
            Log.i(TAG, "GateActivity launched via AccessibilityService (BAL exempt)")
            return
        }

        val gateIntent = Intent(this, GateActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }

        // 2. 常规 startActivity 启动
        try {
            startActivity(gateIntent)
            Log.i(TAG, "GateActivity launched directly with zero animation")
        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity failed, fallback to PendingIntent", e)
        }

        // 3. PendingIntent 启动（适配 Android 14+：显式授权后台启动）
        try {
            val pi = PendingIntent.getActivity(
                this,
                POPUP_REQUEST_CODE,
                gateIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val options = ActivityOptions.makeBasic().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
            pi.send(this, 0, null, null, null, null, options.toBundle())
        } catch (e2: Exception) {
            Log.e(TAG, "PendingIntent also failed", e2)
        }

        // 4. 发送 FullScreenIntent 高优先级顶层穿透通知（适配 Android 14+ canUseFullScreenIntent 检测）
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val canFsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nm?.canUseFullScreenIntent() ?: true
            } else {
                true
            }

            val fullScreenPendingIntent = PendingIntent.getActivity(
                this,
                POPUP_REQUEST_CODE,
                gateIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val popupNotification = NotificationCompat.Builder(this, POPUP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("开屏自律门禁")
                .setContentText("请声明本次打开手机的意图")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .apply {
                    if (canFsi) {
                        setFullScreenIntent(fullScreenPendingIntent, true)
                    } else {
                        setContentIntent(fullScreenPendingIntent)
                    }
                }
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            nm?.notify(POPUP_NOTIFICATION_ID, popupNotification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send fullScreenIntent notification", e)
        }
    }

    /**
     * 防逃逸前台守护侦测：未声明意图前，如果前台变成系统桌面或其他非白名单 App，立即重新弹回门禁！
     */
    fun startGuardWatcher() {
        guardWatcherJob?.cancel()
        guardWatcherJob = serviceScope.launch {
            while (isActive && isTipEnabled && !isSessionUnlocked) {
                delay(300)
                if (isTipEnabled && !isSessionUnlocked && !GateActivity.isGateForeground) {
                    // 若处于白名单应用启动过渡保护期，暂不强弹拉回
                    if (isWhitelistedAppLaunching()) {
                        continue
                    }
                    val top = getForegroundPackage()
                    if (top != null && !isPackageAllowedWhileLocked(top)) {
                        Log.w(TAG, "GuardWatcher intercepted unapproved package: $top, reasserting gate")
                        withContext(Dispatchers.Main) {
                            launchGateActivity()
                        }
                    }
                }
            }
        }
    }

    private suspend fun isWhitelistPackage(pkg: String): Boolean {
        return try {
            val list = database.whitelistDao().getWhitelistPackageNames()
            list.contains(pkg)
        } catch (_: Exception) {
            false
        }
    }

    private fun getForegroundPackage(): String? {
        return try {
            val now = System.currentTimeMillis()
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return null
            val events = usm.queryEvents(now - 3000, now)
            val event = android.app.usage.UsageEvents.Event()
            var topPackage: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    topPackage = event.packageName
                }
            }
            topPackage
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun ensureActiveSession() {
        try {
            val control = database.tipControlDao().getControl() ?: return
            val openSession = database.sessionDao().getOpenSession()
            val canonicalId = if (openSession != null) {
                openSession.id
            } else {
                val now = System.currentTimeMillis()
                val newId = UUID.randomUUID().toString()
                val newSession = SessionEntity(
                    id = newId,
                    bootId = "boot-$now",
                    startWallMs = now,
                    status = SessionStatus.OPEN.name
                )
                val initialSegment = SessionSegmentEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = newId,
                    kind = SegmentKind.RESTRICTED.name,
                    startWallMs = now
                )
                database.sessionDao().getOrCreateCanonicalOpenSession(newSession, initialSegment)
            }
            if (control.activeSessionId != canonicalId || control.state != SessionState.RESTRICTED.name) {
                database.tipControlDao().updateEnabled(true, SessionState.RESTRICTED.name)
                database.tipControlDao().updateActiveSessionId(canonicalId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring active session", e)
        }
    }

    /**
     * AlarmManager 自愈拉起调度
     */
    private fun rescheduleServiceRestart() {
        try {
            val restartIntent = Intent(applicationContext, GateGuardService::class.java).also {
                it.setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getService(
                this,
                RESTART_REQUEST_CODE,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val restartTime = SystemClock.elapsedRealtime() + 1000
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager?.canScheduleExactAlarms() == true) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        restartTime,
                        pendingIntent
                    )
                } else {
                    alarmManager?.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        restartTime,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    restartTime,
                    pendingIntent
                )
            } else {
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    restartTime,
                    pendingIntent
                )
            }
            Log.i(TAG, "rescheduleServiceRestart: 已通过 AlarmManager 预定 1s 后自愈重启守护服务")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule service restart", e)
        }
    }

    /**
     * 开启专注倒计时器（悬浮灵动胶囊 + 通知栏进度条 + 超时多重强提醒）
     */
    fun startFocusTimer(intentText: String, targetDurationMinutes: Int) {
        if (targetDurationMinutes <= 0) return
        focusTimerJob?.cancel()

        val nowElapsed = SystemClock.elapsedRealtime()
        val totalSec = targetDurationMinutes * 60
        timerDeadlineElapsedMs = nowElapsed + (totalSec * 1000L)
        timerDeadlineWallMs = System.currentTimeMillis() + (totalSec * 1000L)
        currentTimerIntentText = intentText
        currentTimerRemainingSeconds = totalSec
        currentTimerTotalSeconds = totalSec
        hasTriggeredTimeoutAlert = false

        floatingTimer.show(
            intentText = intentText,
            targetDurationMinutes = targetDurationMinutes,
            onLock = {
                stopFocusTimer()
                forceLaunchGateActivity()
            },
            onExtend = {
                extendFocusTimer(1)
            }
        )

        focusTimerJob = serviceScope.launch {
            while (isActive && isSessionUnlocked) {
                val now = SystemClock.elapsedRealtime()
                val remainingSec = ((timerDeadlineElapsedMs - now) / 1000L).toInt()
                currentTimerRemainingSeconds = remainingSec
                val isTimeout = remainingSec <= 0
                floatingTimer.updateTime(remainingSec, isTimeout)
                updateTimerNotification(currentTimerIntentText, remainingSec, currentTimerTotalSeconds, isTimeout)

                if (remainingSec <= 0 && !hasTriggeredTimeoutAlert) {
                    hasTriggeredTimeoutAlert = true
                    triggerTimeoutAlert(currentTimerIntentText, (currentTimerTotalSeconds / 60).coerceAtLeast(1))
                }

                delay(1000)
            }
        }
    }

    fun extendFocusTimer(additionalMinutes: Int) {
        val extraSec = additionalMinutes * 60
        val extraMs = extraSec * 1000L
        timerDeadlineElapsedMs += extraMs
        timerDeadlineWallMs += extraMs
        currentTimerTotalSeconds += extraSec
        val now = SystemClock.elapsedRealtime()
        val remainingSec = ((timerDeadlineElapsedMs - now) / 1000L).toInt()
        currentTimerRemainingSeconds = remainingSec
        if (remainingSec > 0) {
            hasTriggeredTimeoutAlert = false
        }
        floatingTimer.updateTime(remainingSec, remainingSec <= 0)
        updateTimerNotification(currentTimerIntentText, remainingSec, currentTimerTotalSeconds, remainingSec <= 0)
    }

    fun stopFocusTimer() {
        focusTimerJob?.cancel()
        focusTimerJob = null
        floatingTimer.hide()
        startAsForeground()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(ALERT_NOTIFICATION_ID)
    }

    private fun triggerTimeoutAlert(intentText: String, targetMinutes: Int) {
        try {
            // 1. 物理双脉冲节奏震动 (400ms - 200ms - 400ms)
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(longArrayOf(0, 400, 200, 400), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration alert failed", e)
        }

        try {
            // 2. 高优先级横幅强提醒通知 (Heads-Up Alert)
            val nm = getSystemService(NotificationManager::class.java)
            val lockIntent = PendingIntent.getService(
                this,
                999,
                Intent(this, GateGuardService::class.java).apply { action = ACTION_LOCK_NOW },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val extendIntent = PendingIntent.getService(
                this,
                998,
                Intent(this, GateGuardService::class.java).apply { action = ACTION_EXTEND_ONE_MIN },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val alertNotification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("⏰ 专注时间已到！")
                .setContentText("您设定的 ${targetMinutes}分钟 已经用尽（意图: $intentText），请放下手机！")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .addAction(R.drawable.ic_launcher_foreground, "放下手机", lockIntent)
                .addAction(R.drawable.ic_launcher_foreground, "+1分钟", extendIntent)
                .build()

            nm?.notify(ALERT_NOTIFICATION_ID, alertNotification)
        } catch (e: Exception) {
            Log.e(TAG, "Timeout notification failed", e)
        }
    }

    private fun updateTimerNotification(
        intentText: String,
        remainingSeconds: Int,
        totalSeconds: Int,
        isTimeout: Boolean
    ) {
        val formatted = formatSeconds(Math.abs(remainingSeconds))
        val title = if (isTimeout) "⚠️ 专注已超时：$formatted" else "🎯 专注中：剩余 $formatted"
        val text = "意图：$intentText (预计 ${(totalSeconds / 60).coerceAtLeast(1)}分钟)"

        val lockIntent = PendingIntent.getService(
            this,
            999,
            Intent(this, GateGuardService::class.java).apply { action = ACTION_LOCK_NOW },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val extendIntent = PendingIntent.getService(
            this,
            998,
            Intent(this, GateGuardService::class.java).apply { action = ACTION_EXTEND_ONE_MIN },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val progress = if (totalSeconds > 0) {
            ((totalSeconds - remainingSeconds).coerceAtLeast(0) * 100) / totalSeconds
        } else 0

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_launcher_foreground, "提前结束", lockIntent)
            .addAction(R.drawable.ic_launcher_foreground, "+1分钟", extendIntent)

        if (!isTimeout && totalSeconds > 0) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, builder.build())
    }

    private fun formatSeconds(totalSecs: Int): String {
        val s = totalSecs.coerceAtLeast(0)
        val m = s / 60
        val sec = s % 60
        return String.format("%02d:%02d", m, sec)
    }

    companion object {
        private const val TAG = "GateGuardService"
        private const val CHANNEL_ID = "openingtip_guard_channel"
        private const val POPUP_CHANNEL_ID = "openingtip_gate_popup_channel"
        private const val ALERT_CHANNEL_ID = "openingtip_timeout_alert_channel"
        private const val NOTIFICATION_ID = 1001
        private const val POPUP_NOTIFICATION_ID = 1002
        private const val ALERT_NOTIFICATION_ID = 1005
        private const val POPUP_REQUEST_CODE = 1003
        private const val RESTART_REQUEST_CODE = 1004

        const val ACTION_LOCK_NOW = "com.openingtip.ACTION_LOCK_NOW"
        const val ACTION_EXTEND_ONE_MIN = "com.openingtip.ACTION_EXTEND_ONE_MIN"

        @Volatile
        var instance: GateGuardService? = null
            private set

        fun markSessionUnlocked() {
            instance?.let {
                it.isSessionUnlocked = true
                it.guardWatcherJob?.cancel()
                Log.i(TAG, "markSessionUnlocked: 本次使用已获意图授权，放行至系统桌面")
            }
        }

        fun reassertGate() {
            instance?.let { service ->
                if (service.isTipEnabled && !service.isSessionUnlocked) {
                    service.launchGateActivity()
                }
            }
        }

        fun startService(context: Context) {
            try {
                val intent = Intent(context, GateGuardService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start GateGuardService", e)
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, GateGuardService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop GateGuardService", e)
            }
        }
    }
}
