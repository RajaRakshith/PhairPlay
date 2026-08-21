package com.phairplay

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * AppForegroundTracker — reports whether any Activity in this process is resumed (visible).
 *
 * WHY: PhairPlayService must not start MainActivity when the user is already viewing the app.
 * Use resumed/paused — not started/stopped — because on Android TV the Activity often stays
 * STARTED in recents while another app is on screen; only PAUSED fires reliably.
 */
class AppForegroundTracker(application: Application) {

    @Volatile
    var isInForeground: Boolean = false
        private set

    private var resumedActivityCount = 0

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivityCount++
                isInForeground = resumedActivityCount > 0
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivityCount = (resumedActivityCount - 1).coerceAtLeast(0)
                isInForeground = resumedActivityCount > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
