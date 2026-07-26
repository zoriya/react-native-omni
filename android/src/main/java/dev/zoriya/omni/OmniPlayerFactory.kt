package dev.zoriya.omni

import android.util.Log
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.AndroidBackend
import com.margelo.nitro.omni.HybridOmniPlayerFactorySpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerBackend
import com.margelo.nitro.omni.Source

class OmniPlayerFactory(val context: ThemedReactContext) : HybridOmniPlayerFactorySpec() {
    override fun createPlayer(props: Source?, backend: PlayerBackend?): HybridOmniPlayerSpec {
        return OmniPlayer(backend?.android ?: AndroidBackend.VLC).apply {
            source = props
        }
    }
}
