package com.openingtip.data.usage

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Values supplied by Android's daily SCREEN_INTERACTIVE aggregate, without local counters. */
data class SystemScreenUsage(
    val screenOnCount: Int,
    val durationMs: Long,
    val intervalStartMs: Long
)

enum class ScreenUsageStatus { LOADING, AVAILABLE, PERMISSION_REQUIRED, UNAVAILABLE }

data class SystemScreenUsageState(
    val stats: SystemScreenUsage? = null,
    val status: ScreenUsageStatus = ScreenUsageStatus.LOADING
)

// A query can return multiple overlapping daily buckets. Use the newest one, never sum them.
internal fun latestSystemScreenUsage(
    samples: List<SystemScreenUsage>,
    nowMs: Long
): SystemScreenUsage? = samples.filter { it.intervalStartMs <= nowMs }.maxByOrNull { it.intervalStartMs }

class SystemScreenUsageRepository(private val context: Context) {
    suspend fun queryCurrentDay(): SystemScreenUsageState = withContext(Dispatchers.IO) {
        try {
            val appOps = context.getSystemService(AppOpsManager::class.java)
                ?: return@withContext SystemScreenUsageState(status = ScreenUsageStatus.UNAVAILABLE)
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
            val allowed = mode == AppOpsManager.MODE_ALLOWED ||
                (mode == AppOpsManager.MODE_DEFAULT &&
                    context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
            if (!allowed) {
                return@withContext SystemScreenUsageState(status = ScreenUsageStatus.PERMISSION_REQUIRED)
            }
            val manager = context.getSystemService(UsageStatsManager::class.java)
                ?: return@withContext SystemScreenUsageState(status = ScreenUsageStatus.UNAVAILABLE)
            val now = System.currentTimeMillis()
            // Android owns the daily interval boundaries, which need not be local midnight.
            val events = manager.queryEventStats(UsageStatsManager.INTERVAL_DAILY, now - 48 * 60 * 60 * 1000L, now)
                ?: return@withContext SystemScreenUsageState(status = ScreenUsageStatus.UNAVAILABLE)
            val samples = events.filter { it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE }.map {
                SystemScreenUsage(it.count, it.totalTime, it.firstTimeStamp)
            }
            val stats = latestSystemScreenUsage(samples, now)
                ?: return@withContext SystemScreenUsageState(status = ScreenUsageStatus.UNAVAILABLE)
            SystemScreenUsageState(stats, ScreenUsageStatus.AVAILABLE)
        } catch (_: SecurityException) {
            SystemScreenUsageState(status = ScreenUsageStatus.PERMISSION_REQUIRED)
        } catch (_: Exception) {
            // Do not replace missing system data with app sessions or an invented zero.
            SystemScreenUsageState(status = ScreenUsageStatus.UNAVAILABLE)
        }
    }
}
