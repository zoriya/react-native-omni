package dev.zoriya.omni

import android.app.PictureInPictureParams
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.HybridOmniViewSpec

class OmniView(val context: ThemedReactContext) :
    HybridOmniViewSpec(),
    SurfaceHolder.Callback,
    LifecycleEventListener {
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

    private val surfaceView = SurfaceView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        holder.addCallback(this@OmniView)
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

    init {
        context.addLifecycleEventListener(this)
    }

    override fun afterUpdate() {
        Log.e("omniView", "After update called")
        if (!::player.isInitialized) {
            return
        }

        val omniPlayer = player as? OmniPlayer ?: return

        if (boundPlayer === omniPlayer) {
            return
        }

        boundPlayer?.setSurface(null)
        boundPlayer = omniPlayer

        if (surfaceReady) {
            omniPlayer.setSurface(surfaceView.holder)
        }

        if (autoplay == true && !omniPlayer.isPlaying) {
            omniPlayer.play()
        }
    }

    override fun onDropView() {
        restoreUiAfterPip()
        context.removeLifecycleEventListener(this)

        if (!::player.isInitialized) return

        val omniPlayer = player as? OmniPlayer ?: return
        omniPlayer.setSurface(null)
        boundPlayer = null
    }

    override fun onHostResume() {
        Log.e("omni", "resume")
        restoreUiAfterPip()
    }

    override fun onHostPause() {
        Log.e("omni", "pause")
        if (autoPip != true) {
            return
        }

        val activity = context.currentActivity ?: return
        if (activity.isFinishing || activity.isDestroyed || activity.isInPictureInPictureMode) {
            return
        }

        val omniPlayer = boundPlayer ?: return
        if (!omniPlayer.isPlaying) {
            return
        }

        val aspectRatio = if (surfaceView.width > 0 && surfaceView.height > 0) {
            Rational(surfaceView.width, surfaceView.height)
        } else {
            Rational(16, 9)
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .build()
        isolateUiForPip(activity)
        val enteredPip = activity.enterPictureInPictureMode(params)
        if (!enteredPip) {
            restoreUiAfterPip()
        }
    }

    override fun onHostDestroy() {
        restoreUiAfterPip()
    }

    private fun isolateUiForPip(activity: android.app.Activity) {
        if (movedSurfaceToRootForPip) {
            return
        }

        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        rootContent = root

        rootContentViews = (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filter { child -> child.isVisible }
        rootContentViews.forEach { child ->
            child.visibility = View.GONE
        }

        view.removeView(surfaceView)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        movedSurfaceToRootForPip = true
    }

    private fun restoreUiAfterPip() {
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

        view.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        movedSurfaceToRootForPip = false
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        boundPlayer?.setSurface(holder)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        boundPlayer?.setSurface(null)
    }
}
