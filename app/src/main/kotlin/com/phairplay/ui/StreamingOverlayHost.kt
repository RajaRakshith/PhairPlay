package com.phairplay.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.service.PhotoFrame

/**
 * StreamingOverlayHost — Owns the full-screen AirPlay overlay views in [MainActivity].
 *
 * WHY: Overlay show/hide (video, photo, now-playing, PIN) is one responsibility.
 * Keeping it in MainActivity pushed that file past the 400-line contribution limit.
 *
 * Example:
 *   val host = StreamingOverlayHost(context, streamingContainer)
 *   host.attach()
 *   host.onSurfaceReady = { service.notifyVideoSurfaceAvailable() }
 *   host.showStreaming()
 */
class StreamingOverlayHost(
    context: Context,
    private val container: FrameLayout,
) {
    val streamingScreen = StreamingScreen(context)
    private val photoScreen = PhotoScreen(context)
    private val nowPlayingScreen = NowPlayingScreen(context)
    private val pinScreen = PinScreen(context)

    /** Called on the main thread when SurfaceView has a new rendering Surface. */
    var onSurfaceReady: (() -> Unit)?
        get() = streamingScreen.onSurfaceReady
        set(value) { streamingScreen.onSurfaceReady = value }

    /** Called on the main thread when the rendering Surface is destroyed. */
    var onSurfaceLost: (() -> Unit)?
        get() = streamingScreen.onSurfaceLost
        set(value) { streamingScreen.onSurfaceLost = value }

    /**
     * Adds overlay children to [container] and hides non-video screens.
     *
     * WHY: Views are created eagerly so the video Surface exists before RECORD,
     * matching the previous MainActivity.setupOverlayScreens() behavior.
     */
    fun attach() {
        container.addView(streamingScreen)
        container.addView(photoScreen)
        container.addView(nowPlayingScreen)
        container.addView(pinScreen)
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
    }

    fun showStreaming() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        showContainer()
    }

    /**
     * Shows a photo overlay only if the payload decodes.
     *
     * WHY: A failed decode must not hide the current video/audio overlay or the user
     * sees a black screen for a bad /photo body.
     */
    fun showPhoto(photoFrame: PhotoFrame) {
        if (!photoScreen.showPhoto(photoFrame.bytes)) return
        streamingScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        photoScreen.visibility = View.VISIBLE
        showContainer()
    }

    fun showNowPlaying(info: NowPlayingInfo) {
        nowPlayingScreen.update(info)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.VISIBLE
        showContainer()
    }

    fun showPin(pin: String) {
        pinScreen.setPin(pin)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.VISIBLE
        showContainer()
    }

    fun hide() {
        photoScreen.clearPhoto()
        photoScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        container.visibility = View.GONE
    }

    fun getVideoSurface() = streamingScreen.getSurface()

    /** Re-notifies listeners when the Surface survived backgrounding without surfaceCreated. */
    fun notifySurfaceIfReady() = streamingScreen.notifySurfaceIfReady()

    private fun showContainer() {
        container.visibility = View.VISIBLE
        container.bringToFront()
    }
}
