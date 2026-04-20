package dev.zoriya.omni

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.HybridOmniViewSpec

class OmniView(val context: ThemedReactContext) : HybridOmniViewSpec(), SurfaceHolder.Callback {
    override val view = FrameLayout(context)

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

    override lateinit var player: HybridOmniPlayerSpec
    override var autoplay: Boolean? = true
    override var showNotification: Boolean? = true
    override var autoPip: Boolean? = true

    override fun afterUpdate() {
        Log.e("omniView", "After update called")
        if (!::player.isInitialized) {
            Log.w("omniView", "Skipping update because player is not set yet")
            return
        }

        val omniPlayer = player as? OmniPlayer
            ?: run {
                Log.w(
                    "omniView",
                    "Skipping update because player has unexpected type: ${player::class.java.name}"
                )
                return
            }

        if (boundPlayer === omniPlayer) {
            return;
        }

        Log.e("omniView", "Resetting old player")
        boundPlayer?.setSurface(null)
        boundPlayer = omniPlayer

        if (surfaceReady) {
            omniPlayer.setSurface(surfaceView.holder.surface)
        }

        if (autoplay == true && !omniPlayer.isPlaying) {
            omniPlayer.play()
        }
    }

    override fun onDropView() {
        Log.e("omniView", "omni-view dropped")
        if (!::player.isInitialized) return

        val omniPlayer = player as? OmniPlayer ?: return
        omniPlayer.setSurface(null)
        boundPlayer = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        Log.e("omniView", "surface created")
        boundPlayer?.setSurface(holder.surface)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        boundPlayer?.setSurfaceSize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.e("omniView", "surface destroyed")
        surfaceReady = false
        boundPlayer?.setSurface(null)
    }
}
