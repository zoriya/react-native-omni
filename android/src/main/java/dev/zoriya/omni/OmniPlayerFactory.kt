package dev.zoriya.omni

import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.AndroidBackend
import com.margelo.nitro.omni.CastOptions
import com.margelo.nitro.omni.HybridOmniPlayerFactorySpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerBackend
import com.margelo.nitro.omni.Source

class OmniPlayerFactory(val context: ThemedReactContext) : HybridOmniPlayerFactorySpec() {
    override fun createPlayer(
        props: Source?,
        backend: PlayerBackend?,
        cast: CastOptions?,
    ): HybridOmniPlayerSpec {
        return OmniPlayer(backend?.android ?: AndroidBackend.VLC, cast).apply {
            source = props
        }
    }
}
