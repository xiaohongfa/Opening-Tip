package com.openingtip

import com.openingtip.data.usage.SystemScreenUsage
import com.openingtip.data.usage.latestSystemScreenUsage
import org.junit.Assert.*
import org.junit.Test

class SystemScreenUsageTest {
    @Test
    fun usesLatestSystemDayWithoutAddingOlderBuckets() {
        val previous = SystemScreenUsage(80, 12_000_000L, 1_000L)
        val current = SystemScreenUsage(44, 14_297_000L, 90_000L)
        assertEquals(current, latestSystemScreenUsage(listOf(current, previous), 100_000L))
    }

    @Test
    fun preservesSystemCountAndDurationWithoutRecalculatingThem() {
        val system = SystemScreenUsage(13, 123_456L, 5_000L)
        assertEquals(system, latestSystemScreenUsage(listOf(system), 90_000L))
    }

    @Test
    fun missingDataDoesNotBecomeZeroUsage() {
        assertNull(latestSystemScreenUsage(emptyList(), 100_000L))
    }

    @Test
    fun ignoresFutureBucketsAfterClockChange() {
        val current = SystemScreenUsage(4, 60_000L, 10_000L)
        val future = SystemScreenUsage(90, 6_000_000L, 200_000L)
        assertEquals(current, latestSystemScreenUsage(listOf(future, current), 100_000L))
        assertNull(latestSystemScreenUsage(listOf(future), 100_000L))
    }
}
