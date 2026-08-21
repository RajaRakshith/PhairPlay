package com.phairplay

import android.app.ActivityManager
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
    private val startActivity: (Intent, Bundle?) -> Unit = { intent, options ->
        appContext.startActivity(intent, options)
    },
    private val moveOwnTaskToFront: () -> Boolean = { moveOwnTaskToFront(appContext) },
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
        val intent = buildLaunchIntent()
        val pendingIntentOptions = pendingIntentCreationOptions()
        val sendOptions = pendingIntentSendOptions()
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CODE_LAUNCH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            pendingIntentOptions,
        )
        var launched = false
        try {
            launchPendingIntent(pendingIntent, sendOptions)
            launched = true
            Logger.i("SessionLaunchHelper: PendingIntent launch sent for AirPlay session")
        } catch (e: Exception) {
            Logger.w("SessionLaunchHelper: PendingIntent launch blocked — ${e.message}")
        }
        if (!launched) {
            launched = tryDirectLaunch(intent, sendOptions)
        }
        if (launched) {
            val moved = moveOwnTaskToFront()
            Logger.i(
                "SessionLaunchHelper: brought MainActivity to foreground for AirPlay session " +
                    "(moveToFront=$moved)"
            )
        } else {
            Logger.w("SessionLaunchHelper: all launch paths blocked for AirPlay session")
        }
    }

    private fun buildLaunchIntent(): Intent =
        Intent(appContext, MainActivity::class.java).apply {
            addFlags(SessionLaunchIntentFlags.launchFlags())
            putExtra(SessionLaunchIntentFlags.EXTRA_BRING_TASK_TO_FRONT, true)
        }

    private fun tryDirectLaunch(intent: Intent, options: Bundle?): Boolean {
        return try {
            startActivity(intent, options)
            Logger.i("SessionLaunchHelper: direct startActivity succeeded for AirPlay session")
            true
        } catch (e: Exception) {
            Logger.w("SessionLaunchHelper: direct startActivity blocked — ${e.message}")
            false
        }
    }

    /** Creator-side BAL opt-in when building the PendingIntent (required on API 35+). */
    private fun pendingIntentCreationOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }.toBundle()
    }

    /** Sender-side BAL opt-in when calling PendingIntent.send() (required on API 34+). */
    private fun pendingIntentSendOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }.toBundle()
    }

    private companion object {
        private const val REQUEST_CODE_LAUNCH = 42

        /**
         * Moves this app's task to the TV foreground after startActivity/PendingIntent.
         *
         * WHY: On leanback, a successful PendingIntent can resume MainActivity without switching
         * the visible task away from Netflix/YouTube. AppTask.moveToFront() is the TV-safe pattern.
         */
        internal fun moveOwnTaskToFront(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                for (task in am.appTasks) {
                    val base = task.taskInfo.baseActivity ?: continue
                    if (base.packageName == context.packageName) {
                        task.moveToFront()
                        Logger.d("SessionLaunchHelper: AppTask.moveToFront taskId=${task.taskInfo.taskId}")
                        return true
                    }
                }
                Logger.w("SessionLaunchHelper: no own AppTask found for moveToFront")
                false
            } catch (e: Exception) {
                Logger.w("SessionLaunchHelper: AppTask.moveToFront failed — ${e.message}")
                false
            }
        }
    }
}
