package com.openingtip.core.domain

import com.openingtip.core.model.*
import java.util.UUID

/**
 * 规范化的系统使用事件
 */
data class NormalizedUsageEvent(
    val bootId: String,
    val timestampWallMs: Long,
    val eventType: EventType,
    val packageName: String?,
    val className: String? = null,
    val orderInBatch: Int = 0
) {
    enum class EventType {
        ACTIVITY_RESUMED,
        ACTIVITY_PAUSED,
        ACTIVITY_STOPPED,
        KEYGUARD_SHOWN,
        KEYGUARD_HIDDEN,
        SCREEN_INTERACTIVE,
        SCREEN_NON_INTERACTIVE,
        DEVICE_SHUTDOWN,
        DEVICE_STARTUP
    }
}

/**
 * 重建输出结果
 */
data class ReconcileOutput(
    val slices: List<UsageSlice>,
    val appSummaries: List<SessionAppSummary>,
    val gateAndTipMs: Long,
    val systemUiMs: Long,
    val unknownMs: Long
)

/**
 * 纯算法使用统计时间线重算器
 */
class UsageTimelineReconciler {

    /**
     * 重建指定会话的完整使用切片与汇总
     *
     * @param session 目标会话
     * @param segments 该会话的分段列表（RESTRICTED 与 FULL）
     * @param events 该会话时间窗口内的规范化事件序列
     * @param tipPackageName Tip 本身包名
     * @param whitelistPackages 当前限制阶段生效的白名单包名集合
     */
    fun reconcile(
        session: Session,
        segments: List<SessionSegment>,
        events: List<NormalizedUsageEvent>,
        tipPackageName: String = "com.openingtip",
        whitelistPackages: Set<String> = emptySet()
    ): ReconcileOutput {
        val sessionStart = session.startWallMs
        val sessionEnd = session.endWallMs ?: Long.MAX_VALUE

        if (sessionStart >= sessionEnd) {
            return ReconcileOutput(emptyList(), emptyList(), 0L, 0L, 0L)
        }

        // 排序规则：先按时间戳升序；同一时间戳先 PAUSED/STOPPED，再 RESUMED；保留 orderInBatch
        val sortedEvents = events
            .filter { it.timestampWallMs in (sessionStart - 120_000L)..sessionEnd }
            .sortedWith(
                compareBy<NormalizedUsageEvent> { it.timestampWallMs }
                    .thenBy { eventPriority(it.eventType) }
                    .thenBy { it.orderInBatch }
            )

        val rawSlices = mutableListOf<RawSlice>()
        var currentActivePkg: String? = null
        var sliceStartTime = sessionStart
        var isEstimated = false

        for (event in sortedEvents) {
            val eventTime = event.timestampWallMs.coerceIn(sessionStart, sessionEnd)

            when (event.eventType) {
                NormalizedUsageEvent.EventType.ACTIVITY_RESUMED -> {
                    val newPkg = event.packageName
                    if (newPkg != currentActivePkg) {
                        // 结束前一个应用的切片
                        if (eventTime > sliceStartTime) {
                            rawSlices.add(
                                RawSlice(
                                    startWallMs = sliceStartTime,
                                    endWallMs = eventTime,
                                    packageName = currentActivePkg,
                                    isEstimated = isEstimated
                                )
                            )
                        }
                        currentActivePkg = newPkg
                        sliceStartTime = eventTime
                        isEstimated = false
                    }
                }

                NormalizedUsageEvent.EventType.ACTIVITY_PAUSED,
                NormalizedUsageEvent.EventType.ACTIVITY_STOPPED -> {
                    if (event.packageName == currentActivePkg && eventTime > sliceStartTime) {
                        rawSlices.add(
                            RawSlice(
                                startWallMs = sliceStartTime,
                                endWallMs = eventTime,
                                packageName = currentActivePkg,
                                isEstimated = isEstimated
                            )
                        )
                        currentActivePkg = null
                        sliceStartTime = eventTime
                    }
                }

                NormalizedUsageEvent.EventType.SCREEN_NON_INTERACTIVE,
                NormalizedUsageEvent.EventType.KEYGUARD_SHOWN,
                NormalizedUsageEvent.EventType.DEVICE_SHUTDOWN -> {
                    // 屏幕关闭/锁屏/关机截断一切开放前台
                    if (eventTime > sliceStartTime) {
                        rawSlices.add(
                            RawSlice(
                                startWallMs = sliceStartTime,
                                endWallMs = eventTime,
                                packageName = currentActivePkg,
                                isEstimated = isEstimated
                            )
                        )
                    }
                    currentActivePkg = null
                    sliceStartTime = eventTime
                }

                else -> {}
            }
        }

        // 处理末尾未闭合的切片（如果在会话结束前仍有应用未收到 PAUSED）
        if (session.endWallMs != null && session.endWallMs > sliceStartTime) {
            rawSlices.add(
                RawSlice(
                    startWallMs = sliceStartTime,
                    endWallMs = session.endWallMs,
                    packageName = currentActivePkg,
                    isEstimated = currentActivePkg != null // 缺失 PAUSED，标为 ESTIMATED
                )
            )
        }

        // 将 RawSlice 与各 SessionSegment 做区间交集运算，生成不重叠 UsageSlice
        val usageSlices = mutableListOf<UsageSlice>()

        for (segment in segments) {
            val segStart = segment.startWallMs
            val segEnd = segment.endWallMs ?: sessionEnd

            for (raw in rawSlices) {
                val overlapStart = maxOf(segStart, raw.startWallMs)
                val overlapEnd = minOf(segEnd, raw.endWallMs)

                if (overlapStart < overlapEnd) {
                    val duration = overlapEnd - overlapStart
                    val pkg = raw.packageName

                    val category = when {
                        pkg == null -> SliceCategory.UNKNOWN
                        pkg == tipPackageName -> SliceCategory.TIP
                        pkg.startsWith("com.android.systemui") -> SliceCategory.SYSTEM
                        else -> SliceCategory.APP
                    }

                    val quality = if (raw.isEstimated) QualityRating.ESTIMATED else QualityRating.PRECISE
                    val wasAllowlisted = if (segment.kind == SegmentKind.RESTRICTED && pkg != null) {
                        whitelistPackages.contains(pkg)
                    } else null

                    usageSlices.add(
                        UsageSlice(
                            id = UUID.randomUUID().toString(),
                            sessionId = session.id,
                            segmentId = segment.id,
                            startWallMs = overlapStart,
                            endWallMs = overlapEnd,
                            durationMs = duration,
                            packageName = pkg,
                            category = category,
                            quality = quality,
                            wasAllowlisted = wasAllowlisted
                        )
                    )
                }
            }
        }

        // 聚合汇总
        val appSummaries = usageSlices
            .filter { it.category == SliceCategory.APP && it.packageName != null }
            .groupBy { slice ->
                val segment = segments.find { it.id == slice.segmentId }
                Pair(slice.packageName!!, segment?.kind ?: SegmentKind.RESTRICTED)
            }
            .map { (key, slices) ->
                val totalDuration = slices.sumOf { it.durationMs }
                val hasEstimated = slices.any { it.quality == QualityRating.ESTIMATED }
                SessionAppSummary(
                    sessionId = session.id,
                    packageName = key.first,
                    phase = key.second,
                    durationMs = totalDuration,
                    quality = if (hasEstimated) QualityRating.ESTIMATED else QualityRating.PRECISE
                )
            }

        val gateAndTipMs = usageSlices.filter { it.category == SliceCategory.TIP }.sumOf { it.durationMs }
        val systemUiMs = usageSlices.filter { it.category == SliceCategory.SYSTEM }.sumOf { it.durationMs }
        val unknownMs = usageSlices.filter { it.category == SliceCategory.UNKNOWN }.sumOf { it.durationMs }

        return ReconcileOutput(
            slices = usageSlices,
            appSummaries = appSummaries,
            gateAndTipMs = gateAndTipMs,
            systemUiMs = systemUiMs,
            unknownMs = unknownMs
        )
    }

    private fun eventPriority(type: NormalizedUsageEvent.EventType): Int {
        return when (type) {
            NormalizedUsageEvent.EventType.ACTIVITY_PAUSED,
            NormalizedUsageEvent.EventType.ACTIVITY_STOPPED -> 1
            NormalizedUsageEvent.EventType.ACTIVITY_RESUMED -> 2
            NormalizedUsageEvent.EventType.SCREEN_NON_INTERACTIVE,
            NormalizedUsageEvent.EventType.KEYGUARD_SHOWN,
            NormalizedUsageEvent.EventType.DEVICE_SHUTDOWN -> 0
            else -> 3
        }
    }

    private data class RawSlice(
        val startWallMs: Long,
        val endWallMs: Long,
        val packageName: String?,
        val isEstimated: Boolean
    )
}
