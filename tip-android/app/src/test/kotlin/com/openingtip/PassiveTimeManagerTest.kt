package com.openingtip

import com.openingtip.feature.gate.PassiveTimeManager
import org.junit.Assert.*
import org.junit.Test

class PassiveTimeManagerTest {

    @Test
    fun testTargetDailyPassiveMs_isOneAndHalfHours() {
        val ninetyMinutesMs = 90 * 60 * 1000L
        assertEquals(ninetyMinutesMs, PassiveTimeManager.TARGET_DAILY_PASSIVE_MS)
    }

    @Test
    fun testFormatDurationReadable_formatsCorrectly() {
        assertEquals("30秒", PassiveTimeManager.formatDurationReadable(30 * 1000L))
        assertEquals("45分钟", PassiveTimeManager.formatDurationReadable(45 * 60 * 1000L))
        assertEquals("1小时30分", PassiveTimeManager.formatDurationReadable(90 * 60 * 1000L))
        assertEquals("2小时15分", PassiveTimeManager.formatDurationReadable(135 * 60 * 1000L))
    }

    @Test
    fun testFormatTimerDigits_formatsCorrectly() {
        assertEquals("00:45", PassiveTimeManager.formatTimerDigits(45 * 1000L))
        assertEquals("12:34", PassiveTimeManager.formatTimerDigits((12 * 60 + 34) * 1000L))
        assertEquals("01:01:05", PassiveTimeManager.formatTimerDigits((3600 + 65) * 1000L))
    }
}
