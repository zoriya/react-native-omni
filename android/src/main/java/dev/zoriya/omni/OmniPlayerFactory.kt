package dev.zoriya.omni

import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.AndroidBackend
import com.margelo.nitro.omni.CastOptions
import com.margelo.nitro.omni.HybridOmniPlayerFactorySpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerBackend

class OmniPlayerFactory(val context: ThemedReactContext) : HybridOmniPlayerFactorySpec() {
    override fun createPlayer(
        backend: PlayerBackend?,
        cast: CastOptions?,
    ): HybridOmniPlayerSpec {
        return OmniPlayer(backend?.android ?: AndroidBackend.VLC, cast)
    }
}
