package com.openingtip.core.domain

import com.openingtip.core.model.*
import java.util.UUID

/**
 * 当前控制与会话状态快照
 */
data class SessionStateSnapshot(
    val enabled: Boolean = false,
    val sessionState: SessionState = SessionState.DISARMED,
    val healthState: HealthState = HealthState.OK,
    val transitionState: TransitionState = TransitionState.NONE,
    val controlVersion: Long = 1L,
    val currentSession: Session? = null,
    val currentSegment: SessionSegment? = null,
    val activeWhitelistRevision: Long = 1L,
    val pendingCommand: PolicyCommand? = null
)

/**
 * 纯函数状态机 Reducer（严格执行 Section 3 不变式）
 */
class SessionStateMachine {

    /**
     * 核心 Reducer 逻辑
     */
    fun reduce(state: SessionStateSnapshot, event: DomainEvent): SessionStateSnapshot {
        return when (event) {
            is DomainEvent.EnableRequested -> handleEnableRequested(state, event)
            is DomainEvent.UnlockObserved -> handleUnlockObserved(state, event)
            is DomainEvent.WhitelistLaunch -> handleWhitelistLaunch(state, event)
            is DomainEvent.IntentSubmitted -> handleIntentSubmitted(state, event)
            is DomainEvent.LockOrNonInteractive -> handleLockOrNonInteractive(state, event)
            is DomainEvent.SecretSubmitted -> handleSecretSubmitted(state, event)
            is DomainEvent.HomeOpened -> handleHomeOpened(state, event)
            is DomainEvent.ProcessRestored -> handleProcessRestored(state, event)
            is DomainEvent.EssentialCapabilityLost -> handleCapabilityLost(state, event)
        }
    }

    private fun handleEnableRequested(
        state: SessionStateSnapshot,
        event: DomainEvent.EnableRequested
    ): SessionStateSnapshot {
        // 用户明确请求开启
        val nextControlVersion = state.controlVersion + 1L

        return if (event.isInteractiveAndUnlocked) {
            // 已解锁：直接开启会话并进入 RESTRICTED
            val sessionId = UUID.randomUUID().toString()
            val segmentId = UUID.randomUUID().toString()

            val newSession = Session(
                id = sessionId,
                bootId = event.bootId,
                startWallMs = event.wallMs,
                startElapsedMs = event.elapsedMs,
                status = SessionStatus.OPEN,
                createdAt = event.wallMs
            )

            val newSegment = SessionSegment(
                id = segmentId,
                sessionId = sessionId,
                kind = SegmentKind.RESTRICTED,
                startWallMs = event.wallMs,
                startElapsedMs = event.elapsedMs,
                whitelistRevision = state.activeWhitelistRevision
            )

            state.copy(
                enabled = true,
                sessionState = SessionState.RESTRICTED,
                healthState = HealthState.OK,
                controlVersion = nextControlVersion,
                currentSession = newSession,
                currentSegment = newSegment
            )
        } else {
            // 未解锁或非交互状态：进入 ARMED_IDLE
            state.copy(
                enabled = true,
                sessionState = SessionState.ARMED_IDLE,
                healthState = HealthState.OK,
                controlVersion = nextControlVersion,
                currentSession = null,
                currentSegment = null
            )
        }
    }

    private fun handleUnlockObserved(
        state: SessionStateSnapshot,
        event: DomainEvent.UnlockObserved
    ): SessionStateSnapshot {
        if (!state.enabled) {
            // 不变量：enabled=false 永远优先于广播
            return state
        }

        // 不变量：每个主用户最多一个 OPEN Session
        if (state.currentSession != null && state.currentSession.status == SessionStatus.OPEN) {
            // 已经是打开的会话（例如防重复广播）
            return state
        }

        val sessionId = UUID.randomUUID().toString()
        val segmentId = UUID.randomUUID().toString()

        val newSession = Session(
            id = sessionId,
            bootId = event.bootId,
            startWallMs = event.wallMs,
            startElapsedMs = event.elapsedMs,
            status = SessionStatus.OPEN,
            createdAt = event.wallMs
        )

        val newSegment = SessionSegment(
            id = segmentId,
            sessionId = sessionId,
            kind = SegmentKind.RESTRICTED,
            startWallMs = event.wallMs,
            startElapsedMs = event.elapsedMs,
            whitelistRevision = state.activeWhitelistRevision
        )

        return state.copy(
            sessionState = SessionState.RESTRICTED,
            currentSession = newSession,
            currentSegment = newSegment
        )
    }

    private fun handleWhitelistLaunch(
        state: SessionStateSnapshot,
        event: DomainEvent.WhitelistLaunch
    ): SessionStateSnapshot {
        if (!state.enabled || state.sessionState != SessionState.RESTRICTED) {
            return state
        }
        // 打开白名单应用不改变当前会话状态，仍然属于限制阶段
        return state
    }

    private fun handleIntentSubmitted(
        state: SessionStateSnapshot,
        event: DomainEvent.IntentSubmitted
    ): SessionStateSnapshot {
        if (!state.enabled || state.sessionState != SessionState.RESTRICTED) {
            return state
        }
        val currentSession = state.currentSession ?: return state
        val currentSegment = state.currentSegment ?: return state

        // 意图合法性（非空且已由上层排除暗号）
        val trimmed = event.intentText.trim()
        if (trimmed.isEmpty()) {
            return state
        }

        // 结束当前 RESTRICTED 分段
        val closedSegment = currentSegment.copy(
            endWallMs = event.wallMs,
            endElapsedMs = event.elapsedMs,
            durationMs = (event.wallMs - currentSegment.startWallMs).coerceAtLeast(0L)
        )

        // 开启新的 FULL 分段（同一个 Session）
        val newSegment = SessionSegment(
            id = UUID.randomUUID().toString(),
            sessionId = currentSession.id,
            kind = SegmentKind.FULL,
            startWallMs = event.wallMs,
            startElapsedMs = event.elapsedMs
        )

        val updatedSession = currentSession.copy(
            intentText = trimmed,
            intentSubmittedAt = event.wallMs
        )

        // 生成策略释放命令（Outbox）
        val policyCommand = PolicyCommand(
            id = UUID.randomUUID().toString(),
            controlVersion = state.controlVersion,
            type = PolicyCommandType.RELEASE_RESTRICTION,
            targetPolicyRevision = state.activeWhitelistRevision,
            status = CommandStatus.PENDING,
            requestedAt = event.wallMs,
            pendingIntentText = trimmed
        )

        return state.copy(
            sessionState = SessionState.FULL,
            currentSession = updatedSession,
            currentSegment = newSegment,
            pendingCommand = policyCommand
        )
    }

    private fun handleLockOrNonInteractive(
        state: SessionStateSnapshot,
        event: DomainEvent.LockOrNonInteractive
    ): SessionStateSnapshot {
        if (!state.enabled) {
            return state
        }

        val session = state.currentSession
        val segment = state.currentSegment

        if (session == null || session.status != SessionStatus.OPEN) {
            // 无活动会话，确保处于 ARMED_IDLE
            return state.copy(
                sessionState = SessionState.ARMED_IDLE,
                currentSession = null,
                currentSegment = null
            )
        }

        // 关闭当前分段
        val closedSegment = segment?.copy(
            endWallMs = event.wallMs,
            endElapsedMs = event.elapsedMs,
            durationMs = (event.wallMs - segment.startWallMs).coerceAtLeast(0L)
        )

        // 关闭当前会话
        val closedSession = session.copy(
            endWallMs = event.wallMs,
            endElapsedMs = event.elapsedMs,
            status = SessionStatus.CLOSED,
            endReason = event.reason,
            durationMs = (event.wallMs - session.startWallMs).coerceAtLeast(0L)
        )

        return state.copy(
            sessionState = SessionState.ARMED_IDLE,
            currentSession = closedSession,
            currentSegment = closedSegment
        )
    }

    private fun handleSecretSubmitted(
        state: SessionStateSnapshot,
        event: DomainEvent.SecretSubmitted
    ): SessionStateSnapshot {
        // 暗号优先：结束当前会话，原因 DISARMED_BY_SECRET，不创建意图，永久落盘 enabled=false
        val session = state.currentSession
        val segment = state.currentSegment

        val closedSegment = segment?.copy(
            endWallMs = event.wallMs,
            endElapsedMs = event.elapsedMs,
            durationMs = (event.wallMs - segment.startWallMs).coerceAtLeast(0L)
        )

        val closedSession = session?.copy(
            endWallMs = event.wallMs,
            endElapsedMs = event.elapsedMs,
            status = SessionStatus.CLOSED,
            endReason = SessionEndReason.DISARMED_BY_SECRET,
            durationMs = (event.wallMs - session.startWallMs).coerceAtLeast(0L)
        )

        val disarmCommand = PolicyCommand(
            id = UUID.randomUUID().toString(),
            controlVersion = state.controlVersion + 1L,
            type = PolicyCommandType.DISARM_SECRET,
            targetPolicyRevision = state.activeWhitelistRevision,
            status = CommandStatus.PENDING,
            requestedAt = event.wallMs
        )

        return state.copy(
            enabled = false,
            sessionState = SessionState.DISARMED,
            controlVersion = state.controlVersion + 1L,
            currentSession = closedSession,
            currentSegment = closedSegment,
            pendingCommand = disarmCommand
        )
    }

    private fun handleHomeOpened(
        state: SessionStateSnapshot,
        event: DomainEvent.HomeOpened
    ): SessionStateSnapshot {
        // 按当前状态路由，不隐式 Enable
        return state
    }

    private fun handleProcessRestored(
        state: SessionStateSnapshot,
        event: DomainEvent.ProcessRestored
    ): SessionStateSnapshot {
        // 恢复时验证状态一致性，如存在 OPEN 但 bootId 改变，需截断
        val session = state.currentSession ?: return state
        if (session.status == SessionStatus.OPEN && session.bootId != event.bootId) {
            // 跨 boot 必须截断旧会话为 INTERRUPTED
            val closedSession = session.copy(
                status = SessionStatus.INTERRUPTED,
                endReason = SessionEndReason.SHUTDOWN,
                endWallMs = event.wallMs,
                quality = QualityRating.DEGRADED,
                qualityReasons = "跨系统重启自动截断"
            )
            val closedSegment = state.currentSegment?.copy(
                endWallMs = event.wallMs
            )
            return state.copy(
                sessionState = if (state.enabled) SessionState.ARMED_IDLE else SessionState.DISARMED,
                currentSession = closedSession,
                currentSegment = closedSegment
            )
        }
        return state
    }

    private fun handleCapabilityLost(
        state: SessionStateSnapshot,
        event: DomainEvent.EssentialCapabilityLost
    ): SessionStateSnapshot {
        val session = state.currentSession
        val closedSession = session?.copy(
            status = SessionStatus.INTERRUPTED,
            endReason = SessionEndReason.CAPABILITY_LOST,
            endWallMs = event.wallMs,
            quality = QualityRating.DEGRADED,
            qualityReasons = "必要能力丢失: ${event.missingCapability}"
        )

        return state.copy(
            enabled = false,
            sessionState = SessionState.DISARMED,
            healthState = HealthState.RECOVERY_REQUIRED,
            currentSession = closedSession
        )
    }
}
