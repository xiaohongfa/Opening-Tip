package com.openingtip

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openingtip.data.usage.ScreenUsageStatus
import com.openingtip.data.usage.SystemScreenUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only verification against the connected phone's actual Android aggregates. */
@RunWith(AndroidJUnit4::class)
class SystemScreenUsageDeviceTest {
    @Test
    fun displayedMetricsComeFromSystemDailyScreenAggregate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val expected = manager.queryEventStats(UsageStatsManager.INTERVAL_DAILY, now - 48 * 60 * 60 * 1000L, now)
            .filter { it.eventType == UsageEvents.Event.SCREEN_INTERACTIVE && it.firstTimeStamp <= now }
            .maxByOrNull { it.firstTimeStamp }
        assertNotNull("Grant usage access on the test device before running this test", expected)
        val actual = SystemScreenUsageRepository(context).queryCurrentDay()
        assertEquals(ScreenUsageStatus.AVAILABLE, actual.status)
        val stats = actual.stats!!
        assertEquals(expected!!.firstTimeStamp, stats.intervalStartMs)
        // The system may record another screen event between the two queries.
        assertTrue(stats.screenOnCount >= expected.count)
        assertTrue(stats.durationMs >= expected.totalTime)
        println("System aggregate verified: count=${stats.screenOnCount}, durationMs=${stats.durationMs}, startMs=${stats.intervalStartMs}")
    }
}
