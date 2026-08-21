package com.phairplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SessionLaunchPolicyTest — Unit tests for [SessionLaunchPolicy.shouldLaunchMainActivity].
 *
 * WHY: Auto-open must fire only on the rising edge into overlay-active while backgrounded.
 * A false positive relaunches when the user presses Home mid-stream; a false negative leaves
 * Netflix on screen during a new AirPlay session.
 */
class SessionLaunchPolicyTest {

    @Test
    fun `launch on rising edge when background`() {
        assertTrue(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = false,
                isOverlayActive = true,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `no launch when already foreground`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = false,
                isOverlayActive = true,
                isAppInForeground = true,
            )
        )
    }

    @Test
    fun `no launch when overlay stays active and user backgrounds mid-stream`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = true,
                isOverlayActive = true,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `no launch when overlay inactive`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = false,
                isOverlayActive = false,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `no launch on falling edge`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(
                wasOverlayActive = true,
                isOverlayActive = false,
                isAppInForeground = false,
            )
        )
    }

    @Test
    fun `launch again on new session after teardown`() {
        assertFalse(
            SessionLaunchPolicy.shouldLaunchMainActivity(true, false, false)
        )
        assertTrue(
            SessionLaunchPolicy.shouldLaunchMainActivity(false, true, false)
        )
    }
}
