package com.openingtip.feature.gate

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 主动被动屏幕时间管理器
 * 践行理念：“建议每天的被动屏幕时间不要超过 1.5 小时（90 分钟）”
 * 
 * 功能：
 * 1. 提供主动开始/结束被动时间计时的能力；
 * 2. 持久化记录每日被动屏幕时间（按自然日 yyyy-MM-dd）；
 * 3. 统计今日累计、实时结算当前段、计算剩余额度与超标判断；
 * 4. 100% 离线，无网络权限依赖，生命周期安全。
 */
object PassiveTimeManager {

    /**
     * 每日建议被动屏幕时间上限：1.5 小时（90 分钟 = 5,400,000 毫秒）
     */
    const val TARGET_DAILY_PASSIVE_MS = 90 * 60 * 1000L

    private const val PREFS_NAME = "openingtip_passive_time"
    private const val KEY_PREFIX_DAILY = "daily_" // daily_yyyy-MM-dd
    private const val KEY_IS_TIMING = "is_timing"
    private const val KEY_SESSION_START_WALL = "session_start_wall"
    private const val KEY_SESSION_START_ELAPSED = "session_start_elapsed"
    private const val KEY_LAST_SAVED_DATE = "last_saved_date"

    @Volatile
    private var isTimingCached = false
    @Volatile
    private var sessionStartWallMsCached = 0L
    @Volatile
    private var sessionStartElapsedMsCached = 0L

    private val listeners = CopyOnWriteArrayList<(Boolean, Long) -> Unit>()

    fun addListener(listener: (isTiming: Boolean, todayTotalMs: Long) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (isTiming: Boolean, todayTotalMs: Long) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged(context: Context) {
        val timing = isPassiveTimingActive()
        val total = getTodayTotalPassiveMs(context)
        listeners.forEach {
            try {
                it(timing, total)
            } catch (_: Exception) {}
        }
    }

    private fun getTodayDateKey(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    /**
     * 当前是否正在进行被动时间计时
     */
    fun isPassiveTimingActive(): Boolean {
        return isTimingCached
    }

    /**
     * 初始化/恢复状态（在应用启动或 Service 创建时调用）
     */
    @Synchronized
    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isTiming = sp.getBoolean(KEY_IS_TIMING, false)
        if (isTiming) {
            val startWall = sp.getLong(KEY_SESSION_START_WALL, 0L)
            val startElapsed = sp.getLong(KEY_SESSION_START_ELAPSED, 0L)
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()

            // 如果发生跨设备重启或数据异常，进行安全回退结算
            val isBootValid = (nowElapsed >= startElapsed && startElapsed > 0L)
            val elapsedMs = if (isBootValid) {
                (nowElapsed - startElapsed).coerceAtLeast(0L)
            } else {
                (nowWall - startWall).coerceIn(0L, 24 * 3600 * 1000L)
            }

            // 如果异常过大（>6小时），判定为陈旧会话并重置
            if (elapsedMs > 6 * 3600 * 1000L || startWall == 0L) {
                sp.edit()
                    .putBoolean(KEY_IS_TIMING, false)
                    .putLong(KEY_SESSION_START_WALL, 0L)
                    .putLong(KEY_SESSION_START_ELAPSED, 0L)
                    .apply()
                isTimingCached = false
                sessionStartWallMsCached = 0L
                sessionStartElapsedMsCached = 0L
            } else {
                isTimingCached = true
                sessionStartWallMsCached = startWall
                sessionStartElapsedMsCached = startElapsed
            }
        } else {
            isTimingCached = false
            sessionStartWallMsCached = 0L
            sessionStartElapsedMsCached = 0L
        }
    }

    /**
     * 主动开始被动时间计时
     */
    @Synchronized
    fun startPassiveTimer(context: Context): Boolean {
        if (isTimingCached) return false

        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        isTimingCached = true
        sessionStartWallMsCached = nowWall
        sessionStartElapsedMsCached = nowElapsed

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_IS_TIMING, true)
            .putLong(KEY_SESSION_START_WALL, nowWall)
            .putLong(KEY_SESSION_START_ELAPSED, nowElapsed)
            .putString(KEY_LAST_SAVED_DATE, getTodayDateKey())
            .apply()

        notifyChanged(context)
        return true
    }

    /**
     * 主动结束被动时间计时，结算并保存当前段
     * @return 本次计时的毫秒数
     */
    @Synchronized
    fun stopPassiveTimer(context: Context): Long {
        if (!isTimingCached) return 0L

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val isBootValid = (nowElapsed >= sessionStartElapsedMsCached && sessionStartElapsedMsCached > 0L)
        val sessionDuration = if (isBootValid) {
            (nowElapsed - sessionStartElapsedMsCached).coerceAtLeast(0L)
        } else {
            (nowWall - sessionStartWallMsCached).coerceIn(0L, 24 * 3600 * 1000L)
        }

        isTimingCached = false
        sessionStartWallMsCached = 0L
        sessionStartElapsedMsCached = 0L

        val todayKey = getTodayDateKey()
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTodaySaved = sp.getLong(KEY_PREFIX_DAILY + todayKey, 0L)
        val newTodaySaved = currentTodaySaved + sessionDuration

        sp.edit()
            .putBoolean(KEY_IS_TIMING, false)
            .putLong(KEY_SESSION_START_WALL, 0L)
            .putLong(KEY_SESSION_START_ELAPSED, 0L)
            .putLong(KEY_PREFIX_DAILY + todayKey, newTodaySaved)
            .apply()

        notifyChanged(context)
        return sessionDuration
    }

    /**
     * 获取当前正在进行的单次计时时长（毫秒）
     */
    fun getCurrentSessionElapsedMs(): Long {
        if (!isTimingCached) return 0L
        val nowElapsed = SystemClock.elapsedRealtime()
        return (nowElapsed - sessionStartElapsedMsCached).coerceAtLeast(0L)
    }

    /**
     * 获取今日已落盘保存的被动时长（毫秒）
     */
    fun getTodaySavedPassiveMs(context: Context): Long {
        val todayKey = getTodayDateKey()
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getLong(KEY_PREFIX_DAILY + todayKey, 0L)
    }

    /**
     * 获取今日累计总被动时长（已落盘 + 正在进行的当前段）
     */
    fun getTodayTotalPassiveMs(context: Context): Long {
        val saved = getTodaySavedPassiveMs(context)
        val ongoing = if (isTimingCached) getCurrentSessionElapsedMs() else 0L
        return saved + ongoing
    }

    /**
     * 获取今日剩余被动时间（毫秒，<=0 表示已用尽或超标）
     */
    fun getTodayRemainingMs(context: Context): Long {
        return TARGET_DAILY_PASSIVE_MS - getTodayTotalPassiveMs(context)
    }

    /**
     * 今日被动时间是否已超标（>1.5小时）
     */
    fun isTodayExceeded(context: Context): Boolean {
        return getTodayTotalPassiveMs(context) > TARGET_DAILY_PASSIVE_MS
    }

    /**
     * 格式化时长显示（例：45分钟 / 1小时15分）
     */
    fun formatDurationReadable(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val hours = minutes / 60
        val remMin = minutes % 60
        return when {
            hours > 0 -> "${hours}小时${remMin}分"
            minutes > 0 -> "${minutes}分钟"
            else -> "${totalSeconds}秒"
        }
    }

    /**
     * 格式化数字计时器格式（例：12:34 或 01:23:45）
     */
    fun formatTimerDigits(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }
}
