package com.openingtip.core.model

/**
 * 持久业务状态（与短暂 UI 观察状态严格分离）
 */
enum class SessionState {
    DISARMED,
    ARMED_IDLE,
    RESTRICTED,
    FULL
}

/**
 * 附加健康状态
 */
enum class HealthState {
    OK,
    DEGRADED,
    RECOVERY_REQUIRED
}

/**
 * 临时转换状态
 */
enum class TransitionState {
    NONE,
    APPLYING_RESTRICTION,
    RELEASING_RESTRICTION
}

/**
 * 运行模式
 */
enum class OperationMode {
    CONSUMER,
    MANAGED
}

/**
 * 会话生命周期状态
 */
enum class SessionStatus {
    OPEN,
    CLOSED,
    INTERRUPTED
}

/**
 * 会话阶段类型
 */
enum class SegmentKind {
    RESTRICTED,
    FULL
}

/**
 * 切片统计类别
 */
enum class SliceCategory {
    APP,
    TIP,
    SYSTEM,
    UNKNOWN
}

/**
 * 统计与区间质量标记
 */
enum class QualityRating {
    PRECISE,
    ESTIMATED,
    DEGRADED,
    CLOCK_CHANGED
}

/**
 * 策略命令类型
 */
enum class PolicyCommandType {
    APPLY_RESTRICTION,
    RELEASE_RESTRICTION,
    DISARM_SECRET
}

/**
 * 策略执行状态
 */
enum class CommandStatus {
    PENDING,
    APPLIED,
    FAILED
}

/**
 * 会话结束原因
 */
enum class SessionEndReason {
    LOCKED_OR_NON_INTERACTIVE,
    DISARMED_BY_SECRET,
    SHUTDOWN,
    CAPABILITY_LOST,
    USER_MANUAL,
    SCREEN_OFF
}

/**
 * 会话核心数据模型
 */
data class Session(
    val id: String,
    val userSerial: Int = 0,
    val bootId: String,
    val startWallMs: Long,
    val startElapsedMs: Long? = null,
    val endWallMs: Long? = null,
    val endElapsedMs: Long? = null,
    val status: SessionStatus = SessionStatus.OPEN,
    val intentText: String? = null,
    val intentSubmittedAt: Long? = null,
    val endReason: SessionEndReason? = null,
    val quality: QualityRating = QualityRating.PRECISE,
    val qualityReasons: String? = null,
    val durationMs: Long = 0L,
    val statsRevision: Long = 1L,
    val createdAt: Long = startWallMs
)

/**
 * 会话分段模型（每个会话最多两个分段：RESTRICTED 和 FULL）
 */
data class SessionSegment(
    val id: String,
    val sessionId: String,
    val kind: SegmentKind,
    val startWallMs: Long,
    val endWallMs: Long? = null,
    val startElapsedMs: Long? = null,
    val endElapsedMs: Long? = null,
    val whitelistRevision: Long? = null,
    val durationMs: Long = 0L
)

/**
 * 使用时间切片（纯不重叠切片）
 */
data class UsageSlice(
    val id: String,
    val sessionId: String,
    val segmentId: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val durationMs: Long = (endWallMs - startWallMs).coerceAtLeast(0L),
    val packageName: String? = null,
    val userSerial: Int = 0,
    val labelSnapshot: String? = null,
    val category: SliceCategory = SliceCategory.APP,
    val quality: QualityRating = QualityRating.PRECISE,
    val source: String = "USAGE_EVENTS",
    val wasAllowlisted: Boolean? = null
)

/**
 * 应用聚合统计
 */
data class SessionAppSummary(
    val sessionId: String,
    val packageName: String,
    val phase: SegmentKind,
    val durationMs: Long,
    val labelSnapshot: String? = null,
    val quality: QualityRating = QualityRating.PRECISE
)

/**
 * 白名单实体
 */
data class WhitelistEntry(
    val userSerial: Int = 0,
    val packageName: String,
    val preferredComponent: String? = null,
    val labelCache: String? = null,
    val selectedAt: Long,
    val available: Boolean = true
)

/**
 * 策略命令 Outbox 记录
 */
data class PolicyCommand(
    val id: String,
    val controlVersion: Long,
    val type: PolicyCommandType,
    val targetPolicyRevision: Long,
    val status: CommandStatus = CommandStatus.PENDING,
    val requestedAt: Long,
    val appliedAt: Long? = null,
    val pendingIntentText: String? = null,
    val errorCode: String? = null
)

/**
 * 历史会话展示报告
 */
data class SessionReport(
    val session: Session,
    val segments: List<SessionSegment>,
    val topApps: List<SessionAppSummary>,
    val allApps: List<SessionAppSummary>,
    val restrictedDurationMs: Long,
    val fullDurationMs: Long,
    val gateAndTipMs: Long,
    val systemUiMs: Long,
    val unknownMs: Long,
    val isFirstUsage: Boolean = false
)
