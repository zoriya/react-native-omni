package dev.zoriya.omni

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.HybridOmniViewSpec
import com.margelo.nitro.omni.SubtitleAssets
import java.lang.ref.WeakReference

class OmniView(val context: ThemedReactContext) :
    HybridOmniViewSpec(),
    SurfaceHolder.Callback,
    LifecycleEventListener,
    View.OnLayoutChangeListener,
    Player.Listener {
    companion object {
        private var activeView = WeakReference<OmniView>(null)

        @Suppress("unused")
        @JvmStatic
        fun onActivityPipTransitionToPip(activity: android.app.Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                activeView.get()?.isolateUiForPipIfNeeded(activity)
            }
        }

        @Suppress("unused")
        @JvmStatic
        fun onActivityPipModeChanged(
            activity: android.app.Activity,
            isInPictureInPictureMode: Boolean
        ) {
            if (isInPictureInPictureMode) {
                activeView.get()?.isolateUiForPipIfNeeded(activity, afterEnteredPip = true)
            } else {
                activeView.get()?.restoreUiAfterPip()
            }
        }
    }

    override val view = object : FrameLayout(context) {
        // React Native manages layout via Yoga and swallows requestLayout() calls from native
        // views. When children are added/removed (e.g. during PIP transitions), addView triggers
        // requestLayout() but it never results in a measure+layout pass. Override to force one.
        // see: https://github.com/facebook/react-native/blob/d19afc73f5048f81656d0b4424232ce6d69a6368/ReactAndroid/src/main/java/com/facebook/react/views/toolbar/ReactToolbar.java#L166
        private val layoutRunnable = Runnable {
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
            layout(left, top, right, bottom)
        }

        override fun requestLayout() {
            super.requestLayout()
            post(layoutRunnable)
        }
    }

    private val contentFrame = AspectRatioFrameLayout(context).apply {
        setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        view.addView(this)
    }

    private val surfaceView = SurfaceView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        holder.addCallback(this@OmniView)
        addOnLayoutChangeListener(this@OmniView)
        contentFrame.addView(this)
    }

    private val subtitleView = SubtitleView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setUserDefaultStyle()
        setUserDefaultTextSize()
        view.addView(this)
    }

    private var surfaceReady = false
    private var boundPlayer: OmniPlayer? = null
    private var rootContent: ViewGroup? = null
    private var rootContentViews: List<View> = emptyList()
    private var movedSurfaceToRootForPip = false

    override lateinit var player: HybridOmniPlayerSpec
    override var autoplay: Boolean? = true
    override var autoPip: Boolean? = true

    // Web-only; the native view uses embedded fonts and renders subtitles via VLC.
    override var subtitleAssets: SubtitleAssets? = null

    init {
        context.addLifecycleEventListener(this)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        contentFrame.setAspectRatio(
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            } else {
                0f
            }
        )
    }

    override fun onCues(cueGroup: CueGroup) {
        subtitleView.setCues(cueGroup.cues)
    }

    override fun onLayoutChange(
        view: View?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int
    ) {
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            updatePictureInPictureParams()
        }
    }

    override fun afterUpdate() {
        val curPip = activeView.get()
        when {
            autoPip == true && curPip == this -> {}
            autoPip == true && curPip == null -> {
                activeView = WeakReference(this)
                updatePictureInPictureParams()
            }

            autoPip == true -> {
                throw Error("Only one OmniView can have `autoPip` set at a time.")
            }

            autoPip == false && curPip == this -> {
                activeView = WeakReference(null)
                clearPictureInPictureParams()
            }
        }

        if (!::player.isInitialized) {
            return
        }

        val omniPlayer = player as? OmniPlayer ?: return

        if (boundPlayer === omniPlayer) {
            return
        }

        boundPlayer?.localPlayer?.removeListener(this)
        boundPlayer?.setVideoView(null)
        boundPlayer = omniPlayer
        omniPlayer.localPlayer.addListener(this)

        if (surfaceReady) {
            omniPlayer.setVideoView(surfaceView)
        }

        if (autoplay == true && !omniPlayer.isPlaying) {
            omniPlayer.play()
        }
    }

    override fun onDropView() {
        restoreUiAfterPip()
        if (activeView.get() == this) {
            activeView = WeakReference(null)
            clearPictureInPictureParams()
        }
        surfaceView.removeOnLayoutChangeListener(this)
        context.removeLifecycleEventListener(this)

        if (!::player.isInitialized) return

        val omniPlayer = player as? OmniPlayer ?: return
        boundPlayer?.localPlayer?.removeListener(this)
        omniPlayer.setVideoView(null)
        boundPlayer = null
    }

    override fun onHostResume() {
        restoreUiAfterPip()
        updatePictureInPictureParams()
    }

    override fun onHostPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || autoPip != true) {
            return
        }

        val activity = context.currentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed || activity.isInPictureInPictureMode) {
            return
        }

        if (boundPlayer == null ||
            boundPlayer?.localPlayer?.isPlaying != true ||
            boundPlayer?.isCasting == true
        ) {
            return
        }

        isolateUiForPipIfNeeded(activity)
        val enteredPip = activity.enterPictureInPictureMode(
            buildPipParams(autoEnterEnabled = false)
        )
        if (!enteredPip) {
            restoreUiAfterPip()
        }
    }

    override fun onHostDestroy() {
        restoreUiAfterPip()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        updatePictureInPictureParams()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        updatePictureInPictureParams()
    }

    private fun updatePictureInPictureParams() {
        val activity = context.currentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed || movedSurfaceToRootForPip) {
            return
        }

        if (boundPlayer?.isCasting == true) {
            clearPictureInPictureParams()
            return
        }

        val autoEnterEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            autoPip == true &&
            boundPlayer?.localPlayer?.isPlaying == true &&
            boundPlayer?.isCasting != true
        activity.setPictureInPictureParams(buildPipParams(autoEnterEnabled))
    }

    private fun clearPictureInPictureParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val activity = context.currentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed) {
            return
        }
        activity.setPictureInPictureParams(
            PictureInPictureParams.Builder()
                .setAutoEnterEnabled(false)
                .build()
        )
    }

    private fun clampedAspectRatio(width: Int, height: Int): Rational {
        val minRatio = 0.42
        val maxRatio = 2.38
        val ratio = width.toDouble() / height.toDouble()
        if (ratio in minRatio..maxRatio) return Rational(width, height)
        val clamped = ratio.coerceIn(minRatio, maxRatio)
        return Rational((clamped * 1000).toInt(), 1000)
    }

    private fun buildPipParams(autoEnterEnabled: Boolean): PictureInPictureParams {
        val aspectRatio = if (surfaceView.width > 0 && surfaceView.height > 0) {
            clampedAspectRatio(surfaceView.width, surfaceView.height)
        } else {
            Rational(16, 9)
        }

        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)

        val sourceRectHint = Rect()
        if (surfaceView.getGlobalVisibleRect(sourceRectHint) && !sourceRectHint.isEmpty) {
            builder.setSourceRectHint(sourceRectHint)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder
                .setAutoEnterEnabled(autoEnterEnabled)
                .setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    fun isolateUiForPipIfNeeded(activity: android.app.Activity, afterEnteredPip: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !afterEnteredPip) {
            return
        }
        if (context.currentActivity !== activity || autoPip != true || boundPlayer == null) {
            return
        }
        if (!afterEnteredPip && boundPlayer?.localPlayer?.isPlaying != true) return
        isolateUiForPip(activity)
    }

    private fun isolateUiForPip(activity: android.app.Activity) {
        if (movedSurfaceToRootForPip) {
            return
        }
        movedSurfaceToRootForPip = true

        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        rootContent = root

        rootContentViews = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filter { child -> child.isVisible }
        rootContentViews.forEach { child ->
            child.visibility = View.GONE
        }

        contentFrame.removeView(surfaceView)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun restoreUiAfterPip() {
        if (!movedSurfaceToRootForPip) {
            return
        }

        val root = rootContent ?: return
        root.removeView(surfaceView)
        rootContent = null

        rootContentViews.forEach { child ->
            child.visibility = View.VISIBLE
        }
        rootContentViews = emptyList()

        contentFrame.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        movedSurfaceToRootForPip = false
    }

    // A surface that is destroyed and recreated while playback runs (the PIP
    // reparent) makes VLC drop its video pipeline; it won't rebuild it for the
    // new surface on its own, so we must ask it to. The rebuild only succeeds
    // once the (PIP) window has settled at its final size — firing it mid-resize
    // leaves the vout stopped — so it is debounced off surfaceChanged.
    private var pendingVideoRebuild = false
    private val rebuildRunnable = Runnable {
        if (!pendingVideoRebuild) return@Runnable
        pendingVideoRebuild = false
        boundPlayer?.rebuildVideoOutput()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        boundPlayer?.setVideoView(surfaceView)
        if (boundPlayer?.localPlayer?.isPlaying == true) pendingVideoRebuild = true
        updatePictureInPictureParams()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        boundPlayer?.updateVideoLayout(width, height)
        if (pendingVideoRebuild) {
            surfaceView.removeCallbacks(rebuildRunnable)
            surfaceView.postDelayed(rebuildRunnable, 200)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        surfaceView.removeCallbacks(rebuildRunnable)
        boundPlayer?.setVideoView(null)
    }
}
