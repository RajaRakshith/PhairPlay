package com.phairplay

/**
 * SessionLaunchPolicy — decides whether to bring [MainActivity] to the foreground.
 *
 * WHY: AirPlay sessions need MainActivity's SurfaceView. Launch only on the rising edge into
 * overlay-active while the app is backgrounded — not when the user presses Home mid-stream.
 *
 * Example:
 *   if (SessionLaunchPolicy.shouldLaunchMainActivity(wasActive, isActive, inForeground)) {
 *       sessionLaunchHelper.launchMainActivity()
 *   }
 */
object SessionLaunchPolicy {

    /**
     * True when overlay just became active and the app is not in the foreground.
     *
     * @param wasOverlayActive Previous overlay-active state (before this signal update).
     * @param isOverlayActive Current overlay-active state from [OverlaySessionPolicy].
     * @param isAppInForeground True while any Activity is started (see [AppForegroundTracker]).
     */
    fun shouldLaunchMainActivity(
        wasOverlayActive: Boolean,
        isOverlayActive: Boolean,
        isAppInForeground: Boolean,
    ): Boolean = isOverlayActive && !wasOverlayActive && !isAppInForeground
}
