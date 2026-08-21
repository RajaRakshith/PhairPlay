package com.phairplay

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.phairplay.util.Logger

/**
 * SessionLaunchHelper — stateful bridge from PhairPlayService overlay signals to MainActivity launch.
 *
 * WHY: The foreground service accepts AirPlay while another TV app is visible. This helper
 * brings PhairPlay forward once per session (rising edge) so the user sees video, PIN, or metadata.
 */
class SessionLaunchHelper(
    private val appContext: Context,
    private val isAppInForeground: () -> Boolean,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val launchPendingIntent: (PendingIntent, Bundle?) -> Unit = { pendingIntent, options ->
        pendingIntent.send(appContext, 0, null, null, null, null, options)
    },
) {
    private var wasOverlayActive = false

    /**
     * Evaluates overlay-active transitions and launches MainActivity when policy allows.
     *
     * @param isOverlayActive Current value from [OverlaySessionPolicy.isOverlayActive].
     */
    fun onOverlayActiveChanged(isOverlayActive: Boolean) {
        val inForeground = isAppInForeground()
        val shouldLaunch = SessionLaunchPolicy.shouldLaunchMainActivity(
            wasOverlayActive = wasOverlayActive,
            isOverlayActive = isOverlayActive,
            isAppInForeground = inForeground,
        )
        Logger.d(
            "SessionLaunchHelper: overlayActive=$isOverlayActive wasOverlayActive=$wasOverlayActive " +
                "inForeground=$inForeground shouldLaunch=$shouldLaunch"
        )
        if (shouldLaunch) {
            mainHandler.post { launchMainActivity() }
        }
        wasOverlayActive = isOverlayActive
    }

    internal fun launchMainActivity() {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val options = backgroundLaunchOptions()
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_LAUNCH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            launchPendingIntent(pendingIntent, options)
            Logger.i("SessionLaunchHelper: brought MainActivity to foreground for AirPlay session")
        } catch (e: Exception) {
            Logger.w("SessionLaunchHelper: startActivity blocked — ${e.message}")
            tryDirectLaunch(intent, options)
        }
    }

    private fun tryDirectLaunch(intent: Intent, options: Bundle?) {
        try {
            appContext.startActivity(intent, options)
            Logger.i("SessionLaunchHelper: direct startActivity succeeded for AirPlay session")
        } catch (e: Exception) {
            Logger.w("SessionLaunchHelper: direct startActivity also blocked — ${e.message}")
        }
    }

    private fun backgroundLaunchOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle()
    }

    private companion object {
        private const val REQUEST_CODE_LAUNCH = 42
    }
}
