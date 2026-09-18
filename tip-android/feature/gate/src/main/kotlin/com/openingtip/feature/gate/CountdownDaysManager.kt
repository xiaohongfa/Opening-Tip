package com.openingtip.feature.gate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class CountdownItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val targetDate: String, // yyyy-MM-dd
    val isPinned: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

object CountdownDaysManager {
    private const val PREFS_NAME = "openingtip_countdown"
    private const val KEY_COUNTDOWNS = "countdown_items"

    private fun getDefaultTargetDate(): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, 100)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    val DEFAULT_ITEMS: List<CountdownItem> by lazy {
        listOf(
            CountdownItem(
                id = "default_goal",
                title = "重要自律目标",
                targetDate = getDefaultTargetDate(),
                isPinned = true
            )
        )
    }

    fun getItems(context: Context): List<CountdownItem> {
        return try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = sp.getString(KEY_COUNTDOWNS, null)
            if (jsonStr.isNullOrBlank()) {
                DEFAULT_ITEMS
            } else {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<CountdownItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        CountdownItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            title = obj.optString("title", "目标"),
                            targetDate = obj.optString("targetDate", getDefaultTargetDate()),
                            isPinned = obj.optBoolean("isPinned", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                if (list.isEmpty()) DEFAULT_ITEMS else list
            }
        } catch (_: Exception) {
            DEFAULT_ITEMS
        }
    }

    fun saveItems(context: Context, items: List<CountdownItem>) {
        try {
            val array = JSONArray()
            items.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("targetDate", item.targetDate)
                    put("isPinned", item.isPinned)
                    put("createdAt", item.createdAt)
                }
                array.put(obj)
            }
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit().putString(KEY_COUNTDOWNS, array.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getPinnedItem(context: Context): CountdownItem {
        val items = getItems(context)
        return items.firstOrNull { it.isPinned } ?: items.firstOrNull() ?: DEFAULT_ITEMS.first()
    }

    fun setPinnedItem(context: Context, itemId: String) {
        val items = getItems(context)
        val updated = items.map {
            it.copy(isPinned = (it.id == itemId))
        }
        saveItems(context, updated)
    }

    fun addItem(context: Context, title: String, targetDate: String, isPinned: Boolean = false) {
        val items = getItems(context).toMutableList()
        val newItem = CountdownItem(
            id = UUID.randomUUID().toString(),
            title = title,
            targetDate = targetDate,
            isPinned = isPinned
        )
        if (isPinned) {
            val unpinned = items.map { it.copy(isPinned = false) }
            items.clear()
            items.addAll(unpinned)
        }
        items.add(0, newItem)
        saveItems(context, items)
    }

    fun deleteItem(context: Context, itemId: String) {
        val items = getItems(context).filterNot { it.id == itemId }
        val finalItems = if (items.isEmpty()) DEFAULT_ITEMS else {
            if (items.none { it.isPinned }) {
                items.mapIndexed { idx, itm -> if (idx == 0) itm.copy(isPinned = true) else itm }
            } else items
        }
        saveItems(context, finalItems)
    }

    fun calculateDaysDiff(targetDateStr: String): Long {
        return try {
            val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val targetDate = dayFormat.parse(targetDateStr) ?: return 0L
            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val targetCal = Calendar.getInstance().apply {
                time = targetDate
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val diffMs = targetCal.timeInMillis - todayCal.timeInMillis
            diffMs / (24 * 3600 * 1000L)
        } catch (_: Exception) {
            0L
        }
    }

    fun formatDaysDisplay(diffDays: Long): String {
        return when {
            diffDays > 0 -> "还有 ${diffDays} 天"
            diffDays == 0L -> "就是今天！"
            else -> "已过 ${-diffDays} 天"
        }
    }
}
