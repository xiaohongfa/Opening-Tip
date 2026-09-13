package com.openingtip.enforcement.managed

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.openingtip.core.domain.*
import com.openingtip.core.model.OperationMode
import com.openingtip.core.model.PolicyCommand

/**
 * 受管设备版 (managed) 限制控制器
 * 依据规范：使用 Device Owner + Lock Task 机制
 */
class ManagedRestrictionController(
    private val context: Context,
    private val whitelistProvider: suspend () -> List<String>
) : RestrictionController {

    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }
    private val adminComponent = ComponentName(context, TipDeviceAdminReceiver::class.java)

    override suspend fun capability(): OperationMode {
        return if (dpm.isDeviceOwnerApp(context.packageName)) {
            OperationMode.MANAGED
        } else {
            OperationMode.CONSUMER
        }
    }

    override suspend fun applyRestricted(command: PolicyCommand): PolicyExecutionResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return PolicyExecutionResult(
                success = false,
                errorCode = "DEVICE_OWNER_NOT_ACTIVE"
            )
        }

        try {
            val whitelist = whitelistProvider()
            val packages = (whitelist + context.packageName).toTypedArray()
            dpm.setLockTaskPackages(adminComponent, packages)

            val features = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME
            dpm.setLockTaskFeatures(adminComponent, features)

            return PolicyExecutionResult(success = true)
        } catch (e: Exception) {
            return PolicyExecutionResult(
                success = false,
                errorCode = e.message ?: "APPLY_LOCK_TASK_FAILED"
            )
        }
    }

    override suspend fun releaseOwnedRestrictions(command: PolicyCommand): PolicyExecutionResult {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return PolicyExecutionResult(success = true) // 降级处理
        }

        try {
            // 允许所有应用，解除 Lock Task 专属锁定
            dpm.setLockTaskPackages(adminComponent, emptyArray())
            return PolicyExecutionResult(success = true)
        } catch (e: Exception) {
            return PolicyExecutionResult(
                success = false,
                errorCode = e.message ?: "RELEASE_LOCK_TASK_FAILED"
            )
        }
    }

    override suspend fun inspectActualPolicy(): ObservedPolicy {
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        val lockTaskModeState = activityManager.lockTaskModeState
        val isLocked = lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED

        return ObservedPolicy(
            isRestricted = isDeviceOwner && dpm.isLockTaskPermitted(context.packageName),
            lockTaskActive = isLocked,
            verifiedRevision = 1L
        )
    }
}
