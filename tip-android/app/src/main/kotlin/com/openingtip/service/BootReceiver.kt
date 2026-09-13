package com.openingtip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openingtip.TipApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启动与版本更新广播接收器
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Received action: $action, 检查是否需要启动自律守护服务")
            val app = context.applicationContext as? TipApplication ?: return
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val control = app.database.tipControlDao().getControl()
                    if (control != null && control.enabled && control.whitelistConfirmed) {
                        GateGuardService.startService(context)
                        Log.i("BootReceiver", "开机自检：Tip 处于启用状态，已拉起 GateGuardService")
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error checking tip status on boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
