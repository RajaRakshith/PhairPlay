package com.phairplay

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionLaunchIntentFlagsTest — Unit tests for [SessionLaunchIntentFlags.launchFlags].
 *
 * WHY: TV auto-open must reuse the existing singleTop task (CLEAR_TOP + SINGLE_TOP), not only
 * REORDER_TO_FRONT, which can resume without switching the visible leanback task.
 */
class SessionLaunchIntentFlagsTest {

    @Test
    fun launchFlagsUseNewTaskClearTopAndSingleTop() {
        val flags = SessionLaunchIntentFlags.launchFlags()
        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun launchFlagsDoNotUseReorderToFront() {
        val flags = SessionLaunchIntentFlags.launchFlags()
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}
