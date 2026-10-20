package dev.zoriya.omni

import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.core.Promise
import com.margelo.nitro.omni.AndroidBackend
import com.margelo.nitro.omni.CastLoadRequest
import com.margelo.nitro.omni.CastOptions
import com.margelo.nitro.omni.HybridOmniPlayerFactorySpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerBackend

@Keep
@DoNotStrip
class OmniPlayerFactory(val context: ThemedReactContext) : HybridOmniPlayerFactorySpec() {
    override fun createPlayer(
        backend: PlayerBackend?,
        cast: CastOptions?,
    ): HybridOmniPlayerSpec {
        return OmniPlayer(backend?.android ?: AndroidBackend.VLC, cast)
    }

    override fun addCastLoadListener(cb: (request: CastLoadRequest) -> Promise<Promise<Unit>>): () -> Unit {
        return OmniCastReceiver.addLoadListener(cb)
    }
}
