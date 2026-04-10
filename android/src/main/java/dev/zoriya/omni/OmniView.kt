package dev.zoriya.omni

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.HybridOmniViewSpec
import org.videolan.libvlc.util.VLCVideoLayout

class OmniView(val context: ThemedReactContext) : HybridOmniViewSpec() {
    private val videoLayout = VLCVideoLayout(context)
    private var viewsAttached = false
    private var boundPlayer: OmniPlayer? = null

    override val view: View = videoLayout

    override lateinit var player: HybridOmniPlayerSpec
    override var autoplay: Boolean? = true
    override var showNotification: Boolean? = true
    override var autoPip: Boolean? = true

    override fun afterUpdate() {
        if (!::player.isInitialized) {
            throw IllegalStateException("Player is null in OmniView")
        }

        val omniPlayer = player as? OmniPlayer
            ?: throw IllegalStateException("Player is not an OmniPlayer in OmniView")
        val vout = omniPlayer.player.vlcVout

        if (boundPlayer !== omniPlayer) {
            boundPlayer?.player?.detachViews()
            viewsAttached = false
            boundPlayer = omniPlayer
        }

        if (!viewsAttached || !vout.areViewsAttached()) {
            omniPlayer.player.attachViews(videoLayout, null, false, false)
            viewsAttached = true
        }

        if (autoplay == true && !omniPlayer.isPlaying) {
            omniPlayer.play()
        }
    }

    override fun onDropView() {
        if (!::player.isInitialized) return

        val omniPlayer = player as? OmniPlayer ?: return
        val vout = omniPlayer.player.vlcVout
        if (vout.areViewsAttached()) {
            omniPlayer.player.detachViews()
        }
        viewsAttached = false
        boundPlayer = null
    }
}
