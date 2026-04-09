package dev.zoriya.omni

import android.graphics.Color
import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.omni.HybridOmniViewSpec

class OmniView(val context: ThemedReactContext) : HybridOmniViewSpec() {
    // View
    override val view: View = View(context)

    // Props
    override var autoplay: Boolean?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var showNotification: Boolean?
        get() = TODO("Not yet implemented")
        set(value) {}
    override var autoPip: Boolean?
        get() = TODO("Not yet implemented")
        set(value) {}
}
