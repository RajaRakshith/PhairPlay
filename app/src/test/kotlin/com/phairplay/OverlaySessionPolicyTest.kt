package com.phairplay

import com.phairplay.service.ProtocolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OverlaySessionPolicyTest — Unit tests for [OverlaySessionPolicy.isOverlayActive].
 *
 * WHY: Keep-screen-on and BACK-during-stream both depend on this predicate.
 * A false negative lets the TV screensaver interrupt mirroring; a false positive
 * blocks BACK and holds the display awake while idle.
 *
 * HOW: Pure function over [ProtocolState] plus optional overlay payloads.
 * Window flag application is Android-only and is not exercised here.
 */
class OverlaySessionPolicyTest {

    /**
     * Test: CONNECTED mirroring keeps the overlay session active.
     *
     * WHY: Screensaver during an active mirror is the bug this change prevents.
     */
    @Test
    fun `CONNECTED mirroring is overlay-active`() {
        assertTrue(
            OverlaySessionPolicy.isOverlayActive(ProtocolState.CONNECTED, null, null, null)
        )
    }

    /**
     * Test: audio-only now-playing is overlay-active even when not CONNECTED.
     *
     * WHY: System-audio AirPlay shows NowPlayingScreen while protocol state may
     * still be ADVERTISING; the TV must stay awake for that overlay too.
     */
    @Test
    fun `audio-only now playing is overlay-active`() {
        assertTrue(
            OverlaySessionPolicy.isOverlayActive(ProtocolState.ADVERTISING, Any(), null, null)
        )
    }

    /**
     * Test: photo overlay is overlay-active.
     *
     * WHY: AirPlay /photo uses the same full-screen container; idle timeout would
     * hide the photo behind the screensaver.
     */
    @Test
    fun `photo overlay is overlay-active`() {
        assertTrue(
            OverlaySessionPolicy.isOverlayActive(ProtocolState.ADVERTISING, null, Any(), null)
        )
    }

    /**
     * Test: PIN pairing overlay is overlay-active.
     *
     * WHY: The user must read the PIN on the TV; screensaver would hide the code.
     */
    @Test
    fun `PIN overlay is overlay-active`() {
        assertTrue(
            OverlaySessionPolicy.isOverlayActive(ProtocolState.ADVERTISING, null, null, "1234")
        )
    }

    /**
     * Test: idle advertising is not overlay-active.
     *
     * WHY: WaitingScreen must not hold FLAG_KEEP_SCREEN_ON or BACK consumption.
     */
    @Test
    fun `idle advertising is not overlay-active`() {
        assertFalse(
            OverlaySessionPolicy.isOverlayActive(ProtocolState.ADVERTISING, null, null, null)
        )
    }

    /**
     * Test: DISABLED and ERROR are not overlay-active.
     *
     * WHY: A stopped or failed receiver should restore normal TV power-saving.
     */
    @Test
    fun `DISABLED and ERROR are not overlay-active`() {
        assertFalse(
            OverlaySessionPolicy.isOverlayActive(ProtocolState.DISABLED, null, null, null)
        )
        assertFalse(
            OverlaySessionPolicy.isOverlayActive(ProtocolState.ERROR, null, null, null)
        )
    }

    /**
     * Test: CONNECTED in the service maps to STREAMING even when Activity defaults to DISABLED.
     *
     * WHY: Home→return recreates MainActivity; syncOverlayFromService must restore the
     * streaming overlay from live service state, not stale Activity fields.
     */
    @Test
    fun `resolveOverlayMode shows streaming for CONNECTED`() {
        assertEquals(
            OverlayMode.STREAMING,
            OverlaySessionPolicy.resolveOverlayMode(ProtocolState.CONNECTED, null, null, null)
        )
    }

    @Test
    fun `resolveOverlayMode hides when Activity defaults with idle service`() {
        assertEquals(
            OverlayMode.HIDE,
            OverlaySessionPolicy.resolveOverlayMode(ProtocolState.DISABLED, null, null, null)
        )
    }

    @Test
    fun `resolveOverlayMode prefers PIN over streaming`() {
        assertEquals(
            OverlayMode.PIN,
            OverlaySessionPolicy.resolveOverlayMode(ProtocolState.CONNECTED, null, null, "1234")
        )
    }
}
