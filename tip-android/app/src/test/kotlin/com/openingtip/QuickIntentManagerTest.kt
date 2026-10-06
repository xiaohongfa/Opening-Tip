package com.openingtip

import com.openingtip.feature.gate.QuickIntentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickIntentManagerTest {
    @Test
    fun missingPreferenceUsesDefaults() {
        assertEquals(QuickIntentManager.DEFAULT_PRESETS, QuickIntentManager.decodePresets(null))
    }

    @Test
    fun deletingEveryPresetRemainsEmptyAfterReload() {
        val saved = emptyList<String>().joinToString("|||")
        assertTrue(QuickIntentManager.decodePresets(saved).isEmpty())
    }

    @Test
    fun customPresetsSurviveReload() {
        val presets = listOf("背单词", "查资料")
        assertEquals(presets, QuickIntentManager.decodePresets(presets.joinToString("|||")))
    }

    @Test
    fun explicitlyRestoredDefaultsSurviveReload() {
        val presets = QuickIntentManager.DEFAULT_PRESETS
        assertEquals(presets, QuickIntentManager.decodePresets(presets.joinToString("|||")))
    }
}