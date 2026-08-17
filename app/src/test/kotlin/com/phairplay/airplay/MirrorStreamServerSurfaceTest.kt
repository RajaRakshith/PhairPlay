package com.phairplay.airplay

import com.phairplay.airplay.handshake.shouldRebuildForSurface
import com.phairplay.airplay.handshake.shouldSkipNotifyRebuild
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MirrorStreamServerSurfaceTest — Unit tests for Surface identity recovery helpers.
 *
 * WHY: After screensaver dismiss or Home→return, a new Surface may be allocated.
 * MediaCodec stays bound to the old object, so video goes black unless the decoder
 * is rebuilt. These helpers are the branch conditions used by the decoder thread.
 *
 * HOW: Compare object identity with placeholders (Any) because android.view.Surface
 * is not available in the JVM test runner.
 */
class MirrorStreamServerSurfaceTest {

    /**
     * Test: a new Surface object must trigger a decoder rebuild.
     *
     * WHY: When TextureView cannot retain the SurfaceTexture, a new Surface is allocated.
     */
    @Test
    fun `rebuild when surface identity changes`() {
        val old = Any()
        val new = Any()
        assertTrue(shouldRebuildForSurface(new, old))
    }

    /**
     * Test: idle (no surface) does not rebuild.
     *
     * WHY: Both-null is the stopped-stream state; rebuilding would create a decoder
     * with no output target.
     */
    @Test
    fun `no rebuild when both null`() {
        assertFalse(shouldRebuildForSurface(null, null))
    }

    /**
     * Test: returning from background (null → new Surface) rebuilds.
     *
     * WHY: If the retained SurfaceTexture is released, the provider is null until
     * the new TextureView is ready; the first non-null Surface must rebuild.
     */
    @Test
    fun `rebuild when returning from background null to new surface`() {
        assertTrue(shouldRebuildForSurface(Any(), null))
    }

    /**
     * Test: notifySurfaceAvailable is a no-op when the live Surface is already bound.
     *
     * WHY: onResume and surfaceCreated both notify; a second rebuild would stall
     * video until the next keyframe.
     */
    @Test
    fun `skip notify rebuild when decoder already on live surface`() {
        val surface = Any()
        assertTrue(shouldSkipNotifyRebuild(surface, surface, hasDecoder = true))
    }

    /**
     * Test: notify still rebuilds when identity matches but no decoder exists.
     *
     * WHY: A stream can be RECORD-active before the first Surface arrives
     * (surfaceCreated after RECORD). hasDecoder=false must not skip.
     */
    @Test
    fun `do not skip notify when decoder missing`() {
        val surface = Any()
        assertFalse(shouldSkipNotifyRebuild(surface, surface, hasDecoder = false))
    }

    /**
     * Test: notify rebuilds when the live Surface is a different object.
     *
     * WHY: Screensaver dismiss replaces the Surface even if a decoder is running.
     */
    @Test
    fun `do not skip notify when surface identity changed`() {
        assertFalse(shouldSkipNotifyRebuild(Any(), Any(), hasDecoder = true))
    }

    /**
     * Test: same Surface reference but invalid must rebuild.
     *
     * WHY: Some devices keep the Java object after buffer teardown; MediaCodec
     * output to that Surface stays black unless we rebuild despite `===`.
     */
    @Test
    fun `rebuild when surface identity matches but is invalid`() {
        val surface = Any()
        assertTrue(shouldRebuildForSurface(surface, surface, liveSurfaceValid = false))
    }

    /**
     * Test: notify must not skip when the live Surface is invalid.
     *
     * WHY: onResume re-notifies before surfaceCreated; an invalid cached Surface
     * must not block proactive decoder rebuild.
     */
    @Test
    fun `do not skip notify when surface identity matches but is invalid`() {
        val surface = Any()
        assertFalse(
            shouldSkipNotifyRebuild(surface, surface, hasDecoder = true, liveSurfaceValid = false)
        )
    }
}
