package com.openingtip

import android.app.Application
import android.util.Log
import com.openingtip.core.database.TipDatabase
import com.openingtip.service.GateGuardService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TipApplication : Application() {
    val database: TipDatabase by lazy {
        TipDatabase.getInstance(this)
    }

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("TipApplication", "FATAL CRASH in thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 检查若自律模式处于启用状态，拉起前台守护服务
        appScope.launch {
            try {
                val control = database.tipControlDao().getControl()
                if (control != null && control.enabled && control.whitelistConfirmed) {
                    GateGuardService.startService(this@TipApplication)
                    Log.i("TipApplication", "应用启动自检：Tip 已启用，已拉起 GateGuardService")
                }
            } catch (e: Exception) {
                Log.e("TipApplication", "Failed to auto-start GateGuardService on app launch", e)
            }
        }
    }
}
