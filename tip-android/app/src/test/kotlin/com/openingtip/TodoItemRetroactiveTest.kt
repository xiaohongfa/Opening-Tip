package com.openingtip

import com.openingtip.core.model.TodoItem
import com.openingtip.core.model.TodoType
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

class TodoItemRetroactiveTest {

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    @Test
    fun testAppendCompletion_withPastTimestamp_sortsChronologically() {
        val now = System.currentTimeMillis()
        val oneDayAgo = now - 24 * 3600 * 1000L
        val twoDaysAgo = now - 48 * 3600 * 1000L

        val item = TodoItem(
            id = "test-1",
            title = "自律阅读",
            type = TodoType.PERMANENT,
            completedCount = 1,
            completionRecordsJson = "[$now]"
        )

        // 补录两天的打卡：先补两天前，再补一天前
        val (count1, json1) = item.appendCompletion(twoDaysAgo)
        assertEquals(2, count1)
        val updatedItem1 = item.copy(completedCount = count1, completionRecordsJson = json1)
        
        val (count2, json2) = updatedItem1.appendCompletion(oneDayAgo)
        assertEquals(3, count2)
        val updatedItem2 = updatedItem1.copy(completedCount = count2, completionRecordsJson = json2)

        val timestamps = updatedItem2.getCompletionTimestamps()
        assertEquals(3, timestamps.size)
        // 验证时间戳按升序排序
        assertEquals(twoDaysAgo, timestamps[0])
        assertEquals(oneDayAgo, timestamps[1])
        assertEquals(now, timestamps[2])
    }

    @Test
    fun testUndoCompletionOnDate_removesCorrectDate() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.SEPTEMBER, 10, 12, 0, 0)
        val day1Ts = cal.timeInMillis
        val day1Key = dayFormat.format(Date(day1Ts))

        cal.set(2026, Calendar.SEPTEMBER, 15, 12, 0, 0)
        val day2Ts = cal.timeInMillis
        val day2Key = dayFormat.format(Date(day2Ts))

        cal.set(2026, Calendar.SEPTEMBER, 17, 12, 0, 0)
        val day3Ts = cal.timeInMillis

        val item = TodoItem(
            id = "test-2",
            title = "早起打卡",
            type = TodoType.PERMANENT,
            completedCount = 3,
            completionRecordsJson = "[$day1Ts,$day2Ts,$day3Ts]"
        )

        // 撤销 9月15日 的打卡
        val (newCount, newJson) = item.undoCompletionOnDate(day2Key)
        assertEquals(2, newCount)

        val updatedItem = item.copy(completedCount = newCount, completionRecordsJson = newJson)
        val remaining = updatedItem.getCompletionTimestamps()
        assertEquals(2, remaining.size)
        assertEquals(day1Ts, remaining[0])
        assertEquals(day3Ts, remaining[1])
    }

    @Test
    fun testUndoCompletionOnDate_nonExistentDate_doesNotModify() {
        val now = System.currentTimeMillis()
        val item = TodoItem(
            id = "test-3",
            title = "背单词",
            type = TodoType.PERMANENT,
            completedCount = 1,
            completionRecordsJson = "[$now]"
        )

        val (count, json) = item.undoCompletionOnDate("2020-01-01")
        assertEquals(1, count)
        assertEquals("[$now]", json)
    }
}
