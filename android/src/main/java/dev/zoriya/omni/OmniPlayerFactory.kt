package dev.zoriya.omni

import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniPlayerFactorySpec
import com.margelo.nitro.omni.HybridOmniPlayerPropsSpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec

class OmniPlayerFactory(val context: ThemedReactContext): HybridOmniPlayerFactorySpec() {
    override fun createPlayer(props: HybridOmniPlayerPropsSpec): HybridOmniPlayerSpec {
        return OmniPlayer().apply {
            source = props
        }
    }
}
