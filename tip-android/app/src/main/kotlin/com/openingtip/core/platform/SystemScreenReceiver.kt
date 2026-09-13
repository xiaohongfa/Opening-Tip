package com.openingtip.core.platform

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * 屏幕锁屏与交互状态监听器（动态注册）
 */
class SystemScreenReceiver(
    private val onScreenOff: () -> Unit,
    private val onUserUnlocked: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF -> {
                onScreenOff()
            }
            Intent.ACTION_SCREEN_ON -> {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked) {
                    // 屏幕点亮但在锁屏界面：0ms 保证重置为闭门锁定状态！
                    onScreenOff()
                } else {
                    onUserUnlocked()
                }
            }
            Intent.ACTION_USER_PRESENT -> {
                onUserUnlocked()
            }
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        context.registerReceiver(this, filter)
    }

    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun isDeviceInteractive(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isInteractive
        }

        fun isDeviceLocked(context: Context): Boolean {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return km.isKeyguardLocked
        }
    }
}
