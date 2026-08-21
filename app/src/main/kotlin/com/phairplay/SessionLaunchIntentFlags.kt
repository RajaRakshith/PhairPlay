package com.phairplay

import android.content.Intent

/**
 * SessionLaunchIntentFlags — pure launch Intent contract for auto-open from [SessionLaunchHelper].
 *
 * WHY: Android TV keeps PhairPlay paused in recents while Netflix is visible. [Intent.FLAG_ACTIVITY_REORDER_TO_FRONT]
 * can resume the Activity without switching the visible task; CLEAR_TOP + SINGLE_TOP with [MainActivity]'s
 * singleTop launch mode routes through onNewIntent and reliably reuses the existing task root.
 */
object SessionLaunchIntentFlags {

    /** Set on the launch Intent so [MainActivity] can call [android.app.ActivityManager.moveTaskToFront]. */
    const val EXTRA_BRING_TASK_TO_FRONT = "com.phairplay.extra.BRING_TASK_TO_FRONT"

    /**
     * Intent flags for launching [MainActivity] from a background [com.phairplay.service.PhairPlayService].
     */
    fun launchFlags(): Int =
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
}
