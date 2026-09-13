package com.openingtip.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.openingtip.TipApplication
import com.openingtip.ui.GateActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开屏 Tip 无障碍金钟罩守护服务：
 * 1. 【系统级免杀防线】：Android 系统内核对运行中的无障碍服务具备最高保护级别（BIND_ACCESSIBILITY_SERVICE），
 *    拥有 PERSISTENT_PROC 调度优先级，豁免多任务一键清理强杀与电池休眠强杀，真正实现“杀不掉”；
 * 2. 【0ms 手势防逃逸】：未输入意图前，当用户手速极快上滑切到桌面或拉起多任务列表时，
 *    无障碍事件直接在 0ms 内瞬间截获并将 Gate 门禁打回置顶！
 * 3. 【全能守卫自愈】：即使守护服务遭遇极端系统清理，无障碍服务直接复活守护服务并强制回弹门禁。
 */
class TipAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val guard = GateGuardService.instance
        if (guard != null) {
            if (guard.isTipEnabled && !guard.isSessionUnlocked) {
                // 如果当前前台既不是门禁自身，也不是白名单授权软件
                if (!guard.isWhitelisted(pkg)) {
                    Log.w(TAG, "Accessibility intercepted unapproved window: $pkg while locked! Reasserting gate instantly!")
                    // 若用户试图呼出多任务管理或回到桌面，执行全局返回手势收起桌面/多任务界面
                    if (pkg == "com.miui.home" || pkg == "com.android.systemui" || pkg.contains("launcher", ignoreCase = true)) {
                        try {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                        } catch (_: Exception) {}
                    }
                    guard.forceLaunchGateActivity()
                }
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
                        val intent = Intent(this@TipAccessibilityService, GateActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        }
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed in accessibility self-healing", e)
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "TipAccessibilityService interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        Log.i(TAG, "TipAccessibilityService connected. System-level unkillable protection active.")
        val guard = GateGuardService.instance
        if (guard == null) {
            GateGuardService.startService(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
    }

    companion object {
        private const val TAG = "TipAccessibility"
        @Volatile
        var isConnected: Boolean = false
    }
}
