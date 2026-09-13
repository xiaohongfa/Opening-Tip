package com.openingtip.core.model

/**
 * 领域输入事件（由串行 Reducer 消费）
 */
sealed interface DomainEvent {
    val wallMs: Long
    val elapsedMs: Long?

    /** 用户在引导或管理页点击开启 Tip 模式 */
    data class EnableRequested(
        override val wallMs: Long,
        override val elapsedMs: Long?,
        val bootId: String,
        val isInteractiveAndUnlocked: Boolean
    ) : DomainEvent

    /** 系统确认屏幕可交互且已解锁 */
    data class UnlockObserved(
        override val wallMs: Long,
        override val elapsedMs: Long?,
        val bootId: String
    ) : DomainEvent

    /** 用户在 RESTRICTED 阶段从 Gate 启动已授权白名单应用 */
    data class WhitelistLaunch(
        override val wallMs: Long,
        override val elapsedMs: Long?,
        val packageName: String,
        val componentName: String?
    ) : DomainEvent

    /** 用户在 Gate 提交非空普通意图 */
    data class IntentSubmitted(
        override val wallMs: Long,
        override val elapsedMs: Long?,
        val intentText: String
    ) : DomainEvent

    /** 屏幕熄灭、锁定或进入非交互状态 */
    data class LockOrNonInteractive(
        override val wallMs: Long,
        override val elapsedMs: Long?,
        val reason: SessionEndReason = SessionEndReason.LOCKED_OR_NON_INTERACTIVE
    ) : DomainEvent

    /** 用户在 Gate 输入暗号并校验成功 */
    data class SecretSubmitted(
        override val wallMs: Long,
        override val elapsedMs: Long?
    ) : DomainEvent

    /** 用户按 Home 或进入管理页 */
    data class HomeOpened(
        override val wallMs: Long,
        override val elapsedMs: Long?
    ) : DomainEvent

    /** 进程被系统回收后重建恢复 */
    data class ProcessRestored(
        override val wallMs: Long,
        override val elapsedMs: Long?,
        val bootId: String
    ) : DomainEvent

    /** 必要权限丢失（如使用情况访问权限被撤销、默认桌面丢失） */
    data class EssentialCapabilityLost(
        override val wallMs: Long,
        override val elapsedMs: Long?,
        val missingCapability: String
    ) : DomainEvent
}
