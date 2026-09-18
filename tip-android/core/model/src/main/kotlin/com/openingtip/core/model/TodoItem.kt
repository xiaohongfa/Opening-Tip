package com.openingtip.core.model

/**
 * 待办事件模式
 */
enum class TodoType {
    /** 常驻模式（如禁欲自律、每日阅读、习惯打卡等，不因单次完成而消失） */
    PERMANENT,

    /** 短期模式（如临时任务、买东西、回电话等，完成可归档或删除） */
    SHORT_TERM
}

/**
 * 待办事件领域模型
 */
data class TodoItem(
    val id: String,
    val title: String,
    val type: TodoType,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val targetTime: String? = null,
    val completedCount: Int = 0,
    val completionRecordsJson: String = "[]",
    val sortOrder: Int = 0
) {
    fun getCompletionTimestamps(): List<Long> {
        if (completionRecordsJson.isBlank() || completionRecordsJson == "[]") return emptyList()
        return try {
            val stripped = completionRecordsJson.trim().removePrefix("[").removeSuffix("]")
            if (stripped.isBlank()) emptyList()
            else stripped.split(",").mapNotNull { it.trim().toLongOrNull() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun appendCompletion(timestamp: Long = System.currentTimeMillis()): Pair<Int, String> {
        val list = getCompletionTimestamps().toMutableList()
        list.add(timestamp)
        list.sort()
        val count = (completedCount + 1).coerceAtLeast(list.size)
        val json = "[" + list.joinToString(",") + "]"
        return Pair(count, json)
    }

    fun undoLastCompletion(): Pair<Int, String> {
        val list = getCompletionTimestamps().toMutableList()
        if (list.isNotEmpty()) {
            list.removeAt(list.size - 1)
        }
        val count = (completedCount - 1).coerceAtLeast(0)
        val json = "[" + list.joinToString(",") + "]"
        return Pair(count, json)
    }

    fun undoCompletionOnDate(dateKey: String): Pair<Int, String> {
        val list = getCompletionTimestamps().toMutableList()
        val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val indexToRemove = list.indexOfLast { dayFormat.format(java.util.Date(it)) == dateKey }
        if (indexToRemove >= 0) {
            list.removeAt(indexToRemove)
        }
        val count = (completedCount - 1).coerceAtLeast(0)
        val json = "[" + list.joinToString(",") + "]"
        return Pair(count, json)
    }
}
