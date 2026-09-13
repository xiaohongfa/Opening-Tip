package com.openingtip.core.domain

import com.openingtip.core.model.*

/**
 * 底层限制执行器接口（由 consumer / managed 模块实现）
 */
interface RestrictionController {
    suspend fun capability(): OperationMode
    suspend fun applyRestricted(command: PolicyCommand): PolicyExecutionResult
    suspend fun releaseOwnedRestrictions(command: PolicyCommand): PolicyExecutionResult
    suspend fun inspectActualPolicy(): ObservedPolicy
}

data class PolicyExecutionResult(
    val success: Boolean,
    val errorCode: String? = null
)

data class ObservedPolicy(
    val isRestricted: Boolean,
    val lockTaskActive: Boolean,
    val verifiedRevision: Long
)

/**
 * 策略 Outbox 协调器（负责崩溃一致性与幂等执行）
 */
class PolicyOutboxCoordinator(
    private val restrictionController: RestrictionController
) {
    /**
     * 执行未决策略命令并返回更新后的命令状态
     */
    suspend fun executeCommand(
        command: PolicyCommand,
        currentControlVersion: Long
    ): PolicyCommand {
        // 版本过期检查：旧回调或过期命令不得覆盖新版本
        if (command.controlVersion < currentControlVersion) {
            return command.copy(
                status = CommandStatus.FAILED,
                errorCode = "EXPIRED_CONTROL_VERSION"
            )
        }

        val result = when (command.type) {
            PolicyCommandType.APPLY_RESTRICTION -> {
                restrictionController.applyRestricted(command)
            }
            PolicyCommandType.RELEASE_RESTRICTION -> {
                restrictionController.releaseOwnedRestrictions(command)
            }
            PolicyCommandType.DISARM_SECRET -> {
                restrictionController.releaseOwnedRestrictions(command)
            }
        }

        return if (result.success) {
            // 核验系统真实生效状态
            val observed = restrictionController.inspectActualPolicy()
            val verified = when (command.type) {
                PolicyCommandType.APPLY_RESTRICTION -> observed.isRestricted
                PolicyCommandType.RELEASE_RESTRICTION -> !observed.isRestricted
                PolicyCommandType.DISARM_SECRET -> !observed.isRestricted
            }

            if (verified) {
                command.copy(
                    status = CommandStatus.APPLIED,
                    appliedAt = System.currentTimeMillis(),
                    errorCode = null
                )
            } else {
                command.copy(
                    status = CommandStatus.FAILED,
                    errorCode = "VERIFICATION_FAILED_ACTUAL_POLICY_MISMATCH"
                )
            }
        } else {
            command.copy(
                status = CommandStatus.FAILED,
                errorCode = result.errorCode ?: "EXECUTION_FAILED"
            )
        }
    }
}
