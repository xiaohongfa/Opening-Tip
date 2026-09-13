package com.openingtip.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.openingtip.core.database.TipDatabase
import com.openingtip.core.database.entity.SessionAppSummaryEntity
import com.openingtip.core.database.entity.UsageSliceEntity
import com.openingtip.core.domain.NormalizedUsageEvent
import com.openingtip.core.domain.UsageTimelineReconciler
import com.openingtip.core.model.Session
import com.openingtip.core.model.SessionSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用统计回补与重建仓库
 */
class UsageStatsRepository(
    private val context: Context,
    private val database: TipDatabase,
    private val reconciler: UsageTimelineReconciler = UsageTimelineReconciler()
) {
    private val usageStatsManager: UsageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    /**
     * 重建指定会话的完整使用切片并持久化到 Room
     */
    suspend fun reconcileSession(session: Session, segments: List<SessionSegment>) = withContext(Dispatchers.IO) {
        val sessionStart = session.startWallMs
        val sessionEnd = session.endWallMs ?: System.currentTimeMillis()

        // 依据规范：向前预留 2 分钟游标以接收迟到事件
        val queryStart = sessionStart - 120_000L
        val queryEnd = sessionEnd

        val rawEvents = queryUsageEvents(queryStart, queryEnd, session.bootId)
        val whitelist = database.whitelistDao().getWhitelistPackageNames().toSet()

        val output = reconciler.reconcile(
            session = session,
            segments = segments,
            events = rawEvents,
            tipPackageName = context.packageName,
            whitelistPackages = whitelist
        )

        val sliceEntities = output.slices.map { slice ->
            UsageSliceEntity(
                id = slice.id,
                sessionId = slice.sessionId,
                segmentId = slice.segmentId,
                startWallMs = slice.startWallMs,
                endWallMs = slice.endWallMs,
                durationMs = slice.durationMs,
                packageName = slice.packageName,
                userSerial = slice.userSerial,
                labelSnapshot = slice.labelSnapshot,
                category = slice.category.name,
                quality = slice.quality.name,
                source = slice.source,
                wasAllowlisted = slice.wasAllowlisted
            )
        }

        val summaryEntities = output.appSummaries.map { summary ->
            SessionAppSummaryEntity(
                sessionId = summary.sessionId,
                packageName = summary.packageName,
                phase = summary.phase.name,
                durationMs = summary.durationMs,
                labelSnapshot = summary.labelSnapshot,
                quality = summary.quality.name
            )
        }

        // 事务内原子替换派生时间线
        database.usageDao().replaceDerivedTimeline(
            sessionId = session.id,
            newSlices = sliceEntities,
            newSummaries = summaryEntities
        )
    }

    private fun queryUsageEvents(beginTime: Long, endTime: Long, bootId: String): List<NormalizedUsageEvent> {
        val result = mutableListOf<NormalizedUsageEvent>()
        try {
            val usageEvents = usageStatsManager.queryEvents(beginTime, endTime) ?: return emptyList()
            val event = UsageEvents.Event()
            var order = 0

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val mappedType = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> NormalizedUsageEvent.EventType.ACTIVITY_RESUMED
                    UsageEvents.Event.ACTIVITY_PAUSED -> NormalizedUsageEvent.EventType.ACTIVITY_PAUSED
                    UsageEvents.Event.ACTIVITY_STOPPED -> NormalizedUsageEvent.EventType.ACTIVITY_STOPPED
                    UsageEvents.Event.KEYGUARD_SHOWN -> NormalizedUsageEvent.EventType.KEYGUARD_SHOWN
                    UsageEvents.Event.KEYGUARD_HIDDEN -> NormalizedUsageEvent.EventType.KEYGUARD_HIDDEN
                    UsageEvents.Event.SCREEN_INTERACTIVE -> NormalizedUsageEvent.EventType.SCREEN_INTERACTIVE
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> NormalizedUsageEvent.EventType.SCREEN_NON_INTERACTIVE
                    UsageEvents.Event.DEVICE_SHUTDOWN -> NormalizedUsageEvent.EventType.DEVICE_SHUTDOWN
                    UsageEvents.Event.DEVICE_STARTUP -> NormalizedUsageEvent.EventType.DEVICE_STARTUP
                    else -> null
                }

                if (mappedType != null) {
                    result.add(
                        NormalizedUsageEvent(
                            bootId = bootId,
                            timestampWallMs = event.timeStamp,
                            eventType = mappedType,
                            packageName = event.packageName,
                            className = event.className,
                            orderInBatch = order++
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // 权限撤销或查询异常时安全返回空列表
        }
        return result
    }
}
