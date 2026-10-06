package com.openingtip.feature.gate

import android.content.Context

/**
 * 快捷意图预设管理器（支持用户自由增删改、持久化保存）
 */
object QuickIntentManager {
    private const val PREFS_NAME = "openingtip_presets"
    private const val KEY_PRESETS = "quick_intent_presets"

    val DEFAULT_PRESETS = listOf(
        "💬 回复消息",
        "💳 扫码支付",
        "🗺️ 查路线地图",
        "🎧 听音乐音频",
        "📚 查阅资料",
        "🎯 专注工作",
        "📞 拨打电话",
        "📦 查看快递"
    )

    fun getPresets(context: Context): List<String> {
        return try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_PRESETS, null)
            decodePresets(raw)
        } catch (_: Exception) {
            DEFAULT_PRESETS
        }
    }

    // null means no saved preference; an empty string means the user removed every tag.
    internal fun decodePresets(raw: String?): List<String> {
        return if (raw == null) {
            DEFAULT_PRESETS
        } else {
            raw.split("|||").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    fun savePresets(context: Context, presets: List<String>) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_PRESETS, presets.joinToString("|||")).apply()
        } catch (_: Exception) {}
    }

    fun resetToDefaults(context: Context) {
        savePresets(context, DEFAULT_PRESETS)
    }
}
