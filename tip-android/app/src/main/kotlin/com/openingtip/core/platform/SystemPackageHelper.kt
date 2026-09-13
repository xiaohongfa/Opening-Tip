package com.openingtip.core.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager

/**
 * 系统底层包名与辅助服务识别工具：
 * 1. 动态放行系统基础框架、UI、电话来电、权限弹窗、文件/媒体选择器等；
 * 2. 动态识别已启用的输入法键盘，打字输入时绝不打断；
 * 3. 动态识别桌面 Launcher，杜绝通过桌面手势或多任务逃离门禁。
 */
object SystemPackageHelper {

    // 系统基础框架、UI、电话来电、权限弹窗、系统文件选择器等基础服务包名
    private val SYSTEM_AUXILIARY_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.miui.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.securityadd",
        "com.miui.securitycenter",
        "com.lbe.security.miui",
        "com.android.documentsui",
        "com.google.android.providers.media.module",
        "com.android.providers.media",
        "com.google.android.gms",
        "com.miui.core",
        "miui",
        "com.xiaomi.xmsf",
        "com.android.phone",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.google.android.dialer"
    )

    // 常见输入法软键盘包名前缀
    private val KNOWN_IME_PREFIXES = listOf(
        "com.sohu.inputmethod",
        "com.baidu.inputmethod",
        "com.google.android.inputmethod",
        "com.iflytek",
        "com.tencent.qqpinyin",
        "com.syntellia.fleksy",
        "com.touchtype.swiftkey",
        "com.emoji.keyboard",
        "com.kika.keyboard"
    )

    @Volatile
    private var cachedImePackages: Set<String>? = null
    @Volatile
    private var lastImeCacheTime: Long = 0L

    @Volatile
    private var cachedLauncherPackages: Set<String>? = null
    @Volatile
    private var lastLauncherCacheTime: Long = 0L

    /**
     * 判断是否为系统底层辅助组件（系统 UI、权限弹窗、系统文件选择器、来电界面等）
     */
    fun isSystemAuxiliaryPackage(pkg: String): Boolean {
        return SYSTEM_AUXILIARY_PACKAGES.contains(pkg)
    }

    /**
     * 判断是否为输入法软键盘（打字输入时不应触发防逃逸拉回）
     */
    fun isInputMethod(context: Context, pkg: String): Boolean {
        // 快速前缀匹配
        if (KNOWN_IME_PREFIXES.any { pkg.startsWith(it) }) {
            return true
        }

        val now = SystemClock.elapsedRealtime()
        val cached = cachedImePackages
        if (cached != null && (now - lastImeCacheTime < 30_000L)) {
            return cached.contains(pkg)
        }

        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val imes = (imm?.enabledInputMethodList ?: imm?.inputMethodList ?: emptyList())
                .map { it.packageName }
                .toSet()
            cachedImePackages = imes
            lastImeCacheTime = now
            imes.contains(pkg)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断是否为系统桌面 Launcher（切到桌面属于逃逸行为，必须拉回 Gate）
     */
    fun isLauncher(context: Context, pkg: String): Boolean {
        if (pkg == "com.miui.home" || pkg == "com.android.launcher3" ||
            pkg == "com.google.android.apps.nexuslauncher" || pkg.contains("launcher", ignoreCase = true)) {
            return true
        }

        val now = SystemClock.elapsedRealtime()
        val cached = cachedLauncherPackages
        if (cached != null && (now - lastLauncherCacheTime < 60_000L)) {
            return cached.contains(pkg)
        }

        return try {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolves = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val launchers = resolves.mapNotNull { it.activityInfo?.packageName }.toSet()
            cachedLauncherPackages = launchers
            lastLauncherCacheTime = now
            launchers.contains(pkg)
        } catch (_: Exception) {
            false
        }
    }
}
