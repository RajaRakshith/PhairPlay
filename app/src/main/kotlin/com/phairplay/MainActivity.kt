package com.phairplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.service.PhairPlayService
import com.phairplay.service.PhotoFrame
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceController
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.ui.HomeFragment
import com.phairplay.ui.SettingsFragment
import com.phairplay.ui.StreamingOverlayHost
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MainActivity — The single Activity hosting PhairPlay's navigation and fragments.
 *
 * WHY: PhairPlay uses a single-Activity architecture with Fragment-based navigation.
 * This is the recommended pattern for Android TV apps: one Activity with swappable
 * Fragments avoids the overhead of Activity transitions and keeps the Leanback
 * launcher integration simple.
 *
 * Layout structure:
 *   ┌─ Nav Panel ──┬─ Content (FrameLayout) ─────────────────┐
 *   │  Home        │  HomeFragment  OR  SettingsFragment      │
 *   │  Settings    │                                          │
 *   └──────────────┴──────────────────────────────────────────┘
 *   [streaming_container] — full-screen overlay (GONE when idle)
 *
 * HOW: D-pad left/right navigation between nav panel and content area.
 * The nav panel items switch fragments. PhairPlayService is started on app launch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navItemHome: TextView
    private lateinit var navItemSettings: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout
    private lateinit var overlayHost: StreamingOverlayHost

    private var service: PhairPlayService? = null
    private var isBound = false
    private var overlayStateObserving = false
    private var currentAirPlayState = ProtocolState.DISABLED
    private var currentPhotoFrame: PhotoFrame? = null
    private var currentNowPlaying: NowPlayingInfo? = null
    private var currentPin: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to PhairPlayService")
            service?.setVideoSurfaceProvider { overlayHost.getVideoSurface() }
            notifyVideoSurfaceAvailable()
            observeOverlayState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Timber.d("MainActivity: unbound from PhairPlayService")
        }
    }

    private var selectedNavIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.d("MainActivity created")
        bindViews()
        setupOverlayHost()
        setupNavigation()
        setupBackPressHandler()

        if (savedInstanceState == null) {
            navigateTo(HomeFragment(), navItemHome)
        }

        ServiceController.start(this)
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        service?.setVideoSurfaceProvider { overlayHost.getVideoSurface() }
        // Re-show the overlay from cached state (StateFlow may not re-emit on rebind).
        if (isOverlayActive()) updateOverlay()
        overlayHost.notifySurfaceIfReady()
        notifyVideoSurfaceAvailable()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PhairPlayService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Drop the Surface reference while backgrounded; keep the service bound so
        // onResume can reattach immediately without waiting for bindService.
        service?.setVideoSurfaceProvider { null }
    }

    override fun onDestroy() {
        OverlaySessionPolicy.setKeepScreenOn(window, false)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            service = null
        }
        super.onDestroy()
        // User Back-exit stops the service so the sender drops the RTSP session.
        // Config-change recreation and Home-background must leave the service running.
        if (isFinishing) {
            Timber.d("MainActivity finishing — stopping service so mirroring doesn't linger")
            ServiceController.stop(this)
        } else {
            Timber.d("MainActivity destroyed (recreation) — leaving service running")
        }
    }

    private fun bindViews() {
        navItemHome        = findViewById(R.id.nav_item_home)
        navItemSettings    = findViewById(R.id.nav_item_settings)
        contentContainer   = findViewById(R.id.content_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    private fun setupOverlayHost() {
        overlayHost = StreamingOverlayHost(this, streamingContainer)
        overlayHost.attach()
        overlayHost.onSurfaceReady = { notifyVideoSurfaceAvailable() }
        overlayHost.onSurfaceLost = { Timber.d("MainActivity: streaming surface lost") }
    }

    private fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) navigateTo(HomeFragment(), navItemHome)
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) navigateTo(SettingsFragment(), navItemSettings)
        }
        setNavSelected(navItemHome, true)
        setNavSelected(navItemSettings, false)
    }

    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        setNavSelected(navItemHome, navItem == navItemHome)
        setNavSelected(navItemSettings, navItem == navItemSettings)
        selectedNavIndex = if (navItem == navItemHome) 0 else 1
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    private fun setNavSelected(item: TextView, selected: Boolean) {
        item.isSelected = selected
        item.setTextColor(
            getColor(if (selected) R.color.text_primary else R.color.nav_item_normal)
        )
    }

    private fun notifyVideoSurfaceAvailable() {
        service?.notifyVideoSurfaceAvailable()
    }

    /**
     * Ignores BACK while an overlay is showing so the Activity is not finished.
     *
     * WHY: Finishing MainActivity stops PhairPlayService, which tears down RTSP.
     * Home still backgrounds the app; BACK during a stream is a no-op.
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isOverlayActive()) {
                    Timber.d("MainActivity: BACK ignored during active stream overlay")
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
    }

    /**
     * Routes TV-remote media keys to the AirPlay sender (DACP reverse control) while audio-only or a
     * stream is showing — so the remote can play/pause/skip what the Mac/iPhone is streaming. Returns
     * false for other keys so normal navigation is unaffected.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val overlayActive = currentNowPlaying != null || currentAirPlayState == ProtocolState.CONNECTED
        if (overlayActive) {
            val command = when (keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                android.view.KeyEvent.KEYCODE_DPAD_CENTER -> com.phairplay.airplay.DacpClient.CMD_PLAY_PAUSE
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> com.phairplay.airplay.DacpClient.CMD_NEXT
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> com.phairplay.airplay.DacpClient.CMD_PREV
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> com.phairplay.airplay.DacpClient.CMD_FF
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> com.phairplay.airplay.DacpClient.CMD_REW
                else -> null
            }
            if (command != null) {
                service?.sendAirPlayRemoteCommand(command)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * On older versions the permission is granted automatically with the manifest declaration.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001
    }

    /**
     * Observes [PhairPlayService.airPlayState] and [PhairPlayService.photoFrame]
     * and shows the appropriate full-screen overlay.
     *
     * Called once after the service is bound. The coroutine is automatically cancelled
     * by [lifecycleScope] when the Activity stops.
     */
    private fun observeOverlayState() {
        if (overlayStateObserving) return
        overlayStateObserving = true
        val svc = service ?: return
        lifecycleScope.launch {
            svc.airPlayState.collectLatest { state ->
                currentAirPlayState = state
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.photoFrame.collectLatest { frame ->
                currentPhotoFrame = frame
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.nowPlaying.collectLatest { info ->
                currentNowPlaying = info
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.pairingPin.collectLatest { pin ->
                currentPin = pin
                updateOverlay()
            }
        }
    }

    private fun updateOverlay() {
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        val pin = currentPin
        when {
            pin != null -> overlayHost.showPin(pin)
            nowPlaying != null -> overlayHost.showNowPlaying(nowPlaying)
            currentAirPlayState == ProtocolState.CONNECTED -> overlayHost.showStreaming()
            photoFrame != null -> overlayHost.showPhoto(photoFrame)
            else -> overlayHost.hide()
        }
        OverlaySessionPolicy.setKeepScreenOn(window, isOverlayActive())
    }

    private fun isOverlayActive(): Boolean = OverlaySessionPolicy.isOverlayActive(
        currentAirPlayState, currentNowPlaying, currentPhotoFrame, currentPin
    )
}
