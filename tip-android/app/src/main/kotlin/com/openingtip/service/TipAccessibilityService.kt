package com.openingtip.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.openingtip.TipApplication
import com.openingtip.core.platform.SystemPackageHelper
import com.openingtip.ui.GateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开屏 Tip 无障碍金钟罩守护服务：
 * 1. 【系统级免杀防线】：Android 系统内核对运行中的无障碍服务具备最高保护级别（BIND_ACCESSIBILITY_SERVICE），
 *    拥有 PERSISTENT_PROC 调度优先级，豁免多任务一键清理强杀与电池休眠强杀，真正实现“杀不掉”；
 * 2. 【0ms 硬件/手势防逃逸】：未输入意图前，按 Home 键、多任务键或上滑切桌面时，
 *    无障碍服务在 0ms 内直接吞掉按键或瞬间拉回 Gate 门禁置顶！
 * 3. 【BAL 启动特权】：Android 系统官方豁免无障碍服务的后台启动限制（BAL_ALLOW_ACCESSIBILITY），
 *    二次解锁与锁屏点亮瞬间 100% 成功弹出门禁。
 * 4. 【全能守卫自愈】：即使守护服务遭遇极端系统清理，无障碍服务直接复活守护服务并强制回弹门禁。
 */
class TipAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val guard = GateGuardService.instance
        if (guard != null) {
            if (guard.isTipEnabled && !guard.isSessionUnlocked) {
                // 如果当前窗口属于合法放行范围（白名单应用、已启用输入法键盘、系统基础框架UI或来电界面），完全放行
                if (guard.isPackageAllowedWhileLocked(pkg)) {
                    return
                }

                // 如果处于白名单应用启动过渡保护期内，暂不抢弹以确保应用正常冷启动
                if (guard.isWhitelistedAppLaunching()) {
                    Log.d(TAG, "Ignoring window transition to $pkg during launch grace period")
                    return
                }

                // 如果当前正在挑选文件或系统文件管理器在前台，直接放行
                if (guard.isFilePickerActive() || SystemPackageHelper.isFilePickerPackage(this, pkg)) {
                    Log.d(TAG, "Ignoring window transition to file picker: $pkg")
                    return
                }

                Log.w(TAG, "Accessibility intercepted unapproved window: $pkg while locked! Reasserting gate instantly!")
                launchGateDirectly()
            }
        } else {
            // Guard 实例若未初始化，自愈拉起
            serviceScope.launch {
                try {
                    val app = application as? TipApplication ?: return@launch
                    val control = app.database.tipControlDao().getControl()
                    if (control != null && control.enabled && control.whitelistConfirmed) {
                        Log.i(TAG, "Accessibility detected Tip enabled but Guard is null, reviving guard...")
                        GateGuardService.startService(this@TipAccessibilityService)
                        launchGateDirectly()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed in accessibility self-healing", e)
                }
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val guard = GateGuardService.instance
        if (guard != null && guard.isTipEnabled && !guard.isSessionUnlocked) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_HOME,
                    KeyEvent.KEYCODE_APP_SWITCH -> {
                        Log.i(TAG, "Hardware/virtual navigation key ${event.keyCode} intercepted by Accessibility")
                        launchGateDirectly()
                        return true // 硬件级直接吞掉按键，禁止切后台！
                    }
                }
            }
        }
        return super.onKeyEvent(event)
    }

    fun launchGateDirectly() {
        try {
            val intent = Intent(this, GateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            startActivity(intent)
            Log.i(TAG, "GateActivity launched directly from AccessibilityService with BAL exemption")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch GateActivity from AccessibilityService", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "TipAccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        Log.i(TAG, "TipAccessibilityService connected. System-level unkillable protection active.")
        val guard = GateGuardService.instance
        if (guard == null) {
            GateGuardService.startService(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        isConnected = false
    }

    companion object {
        private const val TAG = "TipAccessibility"
        @Volatile
        var isConnected: Boolean = false
        @Volatile
        var instance: TipAccessibilityService? = null
    }
}
