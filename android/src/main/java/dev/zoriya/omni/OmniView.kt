package dev.zoriya.omni

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.HybridOmniViewSpec

class OmniView(val context: ThemedReactContext) : HybridOmniViewSpec() {
    private val surfaceView = SurfaceView(context)
    private var surfaceReady = false
    private var boundPlayer: OmniPlayer? = null

    override val view: View = surfaceView

    override lateinit var player: HybridOmniPlayerSpec
    override var autoplay: Boolean? = true
    override var showNotification: Boolean? = true
    override var autoPip: Boolean? = true

    init {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                boundPlayer?.setSurface(holder.surface)
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                surfaceReady = true
                boundPlayer?.setSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                boundPlayer?.setSurface(null)
            }
        })
    }

    override fun afterUpdate() {
        if (!::player.isInitialized) {
            throw IllegalStateException("Player is null in OmniView")
        }

        val omniPlayer = player as? OmniPlayer
            ?: throw IllegalStateException("Player is not an OmniPlayer in OmniView")

        if (boundPlayer !== omniPlayer) {
            boundPlayer?.setSurface(null)
            boundPlayer = omniPlayer
        }

        if (surfaceReady) {
            omniPlayer.setSurface(surfaceView.holder.surface)
        }

        if (autoplay == true && !omniPlayer.isPlaying) {
            omniPlayer.play()
        }
    }

    override fun onDropView() {
        if (!::player.isInitialized) return

        val omniPlayer = player as? OmniPlayer ?: return
        omniPlayer.setSurface(null)
        boundPlayer = null
    }
}
