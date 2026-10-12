package dev.zoriya.omni

import android.app.Application
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.margelo.nitro.core.Promise
import com.margelo.nitro.omni.CastLoadRequest

// built when `Omni_castReceiver` is not set: cast connect is disabled and its
// dependencies are left out of the app.
object OmniCastReceiver {
    fun init(app: Application) {}

    @JvmStatic
    fun onNewIntent(intent: Intent?) {}

    fun addLoadListener(cb: (CastLoadRequest) -> Promise<Promise<Unit>>): () -> Unit = {}

    fun setSession(session: MediaSession?) {}

    fun broadcastMedia(player: OmniPlayer, item: MediaItem, hasPrev: Boolean, hasNext: Boolean) {}

    fun broadcastTracks(player: OmniPlayer) {}
}
