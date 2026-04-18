package dev.zoriya.omni

import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniPlayerFactorySpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.Source

class OmniPlayerFactory(val context: ThemedReactContext): HybridOmniPlayerFactorySpec() {
    override fun createPlayer(props: Source): HybridOmniPlayerSpec {
        return OmniPlayer().apply {
            source = props
        }
    }
}
