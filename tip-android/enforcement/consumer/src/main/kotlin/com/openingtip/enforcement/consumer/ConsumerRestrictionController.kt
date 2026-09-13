package com.openingtip.enforcement.consumer

import com.openingtip.core.domain.*
import com.openingtip.core.model.OperationMode
import com.openingtip.core.model.PolicyCommand

/**
 * 普通安装版 (consumer) 限制控制器
 * 依据规范：仅限制自身桌面启动入口，不采用高频轮询抢前台来伪装系统拦截
 */
class ConsumerRestrictionController : RestrictionController {

    @Volatile
    private var isRestricted = false

    override suspend fun capability(): OperationMode {
        return OperationMode.CONSUMER
    }

    override suspend fun applyRestricted(command: PolicyCommand): PolicyExecutionResult {
        isRestricted = true
        return PolicyExecutionResult(success = true)
    }

    override suspend fun releaseOwnedRestrictions(command: PolicyCommand): PolicyExecutionResult {
        isRestricted = false
        return PolicyExecutionResult(success = true)
    }

    override suspend fun inspectActualPolicy(): ObservedPolicy {
        return ObservedPolicy(
            isRestricted = isRestricted,
            lockTaskActive = false,
            verifiedRevision = 1L
        )
    }
}
