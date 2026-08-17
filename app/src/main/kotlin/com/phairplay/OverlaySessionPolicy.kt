package com.phairplay

import android.view.Window
import android.view.WindowManager
import com.phairplay.service.ProtocolState

/**
 * Which full-screen overlay [MainActivity] should present.
 *
 * WHY: Extracted so Home→return overlay restoration is unit-testable without Robolectric.
 */
enum class OverlayMode { HIDE, PIN, NOW_PLAYING, STREAMING, PHOTO }

/**
 * OverlaySessionPolicy — Decides when an AirPlay overlay must keep the TV awake.
 *
 * WHY: TV screensaver and idle display-off interrupt mirroring. The same overlay-active
 * predicate drives FLAG_KEEP_SCREEN_ON and BACK-key handling so those two cannot drift.
 *
 * Example:
 *   val active = OverlaySessionPolicy.isOverlayActive(state, nowPlaying, photo, pin)
 *   OverlaySessionPolicy.setKeepScreenOn(window, active)
 */
object OverlaySessionPolicy {

    /**
     * Picks the overlay to show from the current AirPlay session signals.
     *
     * WHY: MainActivity caches these fields locally. After Home→return the Activity is often
     * recreated with defaults (DISABLED) while PhairPlayService still holds CONNECTED — the
     * UI must re-read service StateFlow values, not rely on stale caches or async re-collect.
     */
    fun resolveOverlayMode(
        airPlayState: ProtocolState,
        nowPlaying: Any?,
        photoFrame: Any?,
        pin: String?,
    ): OverlayMode = when {
        pin != null -> OverlayMode.PIN
        nowPlaying != null -> OverlayMode.NOW_PLAYING
        airPlayState == ProtocolState.CONNECTED -> OverlayMode.STREAMING
        photoFrame != null -> OverlayMode.PHOTO
        else -> OverlayMode.HIDE
    }

    /**
     * True when a full-screen overlay (PIN, audio-only, mirroring, or photo) is showing.
     *
     * WHY: Screensaver, BACK-to-finish, and keep-screen-on must use one predicate.
     * Duplicating the four-way check in MainActivity caused the 400-line cap to be exceeded
     * and made the two call sites easy to get out of sync.
     */
    fun isOverlayActive(
        airPlayState: ProtocolState,
        nowPlaying: Any?,
        photoFrame: Any?,
        pin: String?,
    ): Boolean = pin != null
        || nowPlaying != null
        || airPlayState == ProtocolState.CONNECTED
        || photoFrame != null

    /**
     * Adds or clears FLAG_KEEP_SCREEN_ON on [window].
     *
     * WHY: Android TVs start a screensaver after idle timeout unless this flag is set.
     * Clearing it when idle restores normal power-saving. No-op flags are cheap; always
     * apply the desired state rather than tracking a local boolean that can desync.
     */
    internal fun setKeepScreenOn(window: Window, keepAwake: Boolean) {
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
