package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger

/**
 * StreamingScreen — Full-screen view that displays the AirPlay video stream.
 *
 * WHY: MediaCodec renders to a [Surface]. [TextureView] is used instead of SurfaceView
 * so the [SurfaceTexture] can survive Home/onStop. SurfaceView destroys its Surface on
 * pause, which leaves MediaCodec drawing to a dead buffer queue (audio keeps playing,
 * video stays black). Returning false from [TextureView.SurfaceTextureListener.onSurfaceTextureDestroyed]
 * keeps the same Surface, so the decoder does not need a rebuild for the common Home path.
 *
 * HOW: Add this view to the streaming_container in activity_main.xml.
 * Call [getSurface] to get the Surface to pass to [VideoDecoder.initialize].
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val textureView: TextureView = TextureView(context).apply { isOpaque = true }

    private var retainedTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    /** Called on the main thread when a rendering Surface is ready. */
    var onSurfaceReady: (() -> Unit)? = null

    /** Called on the main thread when the Surface is released for good (Activity destroy). */
    var onSurfaceLost: (() -> Unit)? = null

    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }
    private var lastSurfaceW = Int.MIN_VALUE
    private var lastSurfaceH = Int.MIN_VALUE

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            if (StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = StreamStats.summary()
            } else if (debugView.visibility != GONE) {
                debugView.visibility = GONE
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)

        addView(textureView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        addView(debugView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 48; leftMargin = 48 })

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val retained = retainedTexture
                if (retained != null && retained !== st) {
                    // Re-bind the TextureView to the SurfaceTexture MediaCodec is still using.
                    runCatching { textureView.setSurfaceTexture(retained) }
                        .onFailure { Logger.e("StreamingScreen: reattach SurfaceTexture failed", it) }
                    // New Surface wrapper so MirrorStreamServer sees identity change and rebuilds.
                    bindSurfaceFromTexture(retained)
                    Logger.d("StreamingScreen: reattached retained SurfaceTexture")
                    onSurfaceReady?.invoke()
                    return
                }
                bindSurfaceFromTexture(st)
                Logger.d("StreamingScreen: Surface ready (TextureView)")
                onSurfaceReady?.invoke()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                Logger.d("StreamingScreen: Surface size ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // Keep the SurfaceTexture so MediaCodec can keep producing frames off-screen.
                Logger.d("StreamingScreen: TextureView hidden — retaining SurfaceTexture")
                return false
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    /** Re-invokes [onSurfaceReady] when a valid Surface already exists. */
    fun notifySurfaceIfReady() {
        val s = surface ?: return
        if (s.isValid) {
            Logger.d("StreamingScreen: Surface already valid — re-notifying")
            onSurfaceReady?.invoke()
        }
    }

    /**
     * Ensures a valid [Surface] exists after the overlay becomes visible.
     *
     * WHY: While [streaming_container] is GONE, TextureView may not allocate a
     * SurfaceTexture until layout after showStreaming. onResume can notify the service
     * before surfaceCreated runs — this closes the gap for manual reopen and auto-launch.
     */
    fun ensureSurfaceReady() {
        if (!isAttachedToWindow) return
        val st = textureView.surfaceTexture
        if (st != null && (surface == null || surface?.isValid != true)) {
            bindSurfaceFromTexture(st)
        }
        notifySurfaceIfReady()
    }

    private fun bindSurfaceFromTexture(st: SurfaceTexture) {
        retainedTexture = st
        surface?.release()
        surface = Surface(st)
    }

    /**
     * Releases the retained SurfaceTexture. Call from Activity.onDestroy so GPU
     * buffers are not leaked after the window is gone.
     */
    fun releaseRetainedSurface() {
        Logger.d("StreamingScreen: releasing retained SurfaceTexture")
        surface?.release()
        surface = null
        retainedTexture?.release()
        retainedTexture = null
        onSurfaceLost?.invoke()
    }

    fun getSurface(): Surface? = surface?.takeIf { it.isValid }

    /**
     * Sizes the TextureView to the decoded video's aspect ratio (letterbox/pillarbox).
     */
    private fun applyAspectFit() {
        val vw = StreamStats.videoWidth
        val vh = StreamStats.videoHeight
        val cw = width
        val ch = height
        val (targetW, targetH) = if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) {
            LayoutParams.MATCH_PARENT to LayoutParams.MATCH_PARENT
        } else {
            val videoRatio = vw.toFloat() / vh
            val containerRatio = cw.toFloat() / ch
            if (videoRatio > containerRatio) cw to (cw / videoRatio).toInt()
            else (ch * videoRatio).toInt() to ch
        }
        if (targetW == lastSurfaceW && targetH == lastSurfaceH) return
        lastSurfaceW = targetW
        lastSurfaceH = targetH
        textureView.layoutParams = (textureView.layoutParams as LayoutParams).apply {
            width = targetW; height = targetH; gravity = Gravity.CENTER
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val REFRESH_MS = 200L
    }
}
