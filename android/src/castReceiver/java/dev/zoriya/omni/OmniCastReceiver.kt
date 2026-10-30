package dev.zoriya.omni

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import androidx.annotation.Keep
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.google.android.gms.cast.tv.media.EditTracksInfoData
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaInfoModifier
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.cast.tv.media.QueueUpdateRequestData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.margelo.nitro.core.Promise
import com.margelo.nitro.omni.CastLoadRequest
import dev.zoriya.omni.utils.ThreadHelper.runOnMainThread
import java.util.concurrent.atomic.AtomicInteger

// cast connect: an android tv app registered on the cast console receives casts
// instead of the web receiver. we only forward the load to js (`useCastLoad`), the
// app then sets a source like for any local playback & we report it back to the
// senders.
@SuppressLint("UnsafeOptInUsageError")
object OmniCastReceiver :
    MediaLoadCommandCallback(),
    Application.ActivityLifecycleCallbacks,
    DefaultLifecycleObserver {
    private var receiver: CastReceiverContext? = null
    private val loadListeners = mutableListOf<(CastLoadRequest) -> Promise<Promise<Unit>>>()

    // a cast can launch the app: its load comes in before js listens to it
    private var pendingLoad: Pair<MediaLoadRequestData, TaskCompletionSource<MediaLoadRequestData>>? = null

    fun init(app: Application) {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        val ctx = try {
            CastReceiverContext.initInstance(app)
            CastReceiverContext.getInstance()
        } catch (e: Throwable) {
            android.util.Log.w("OmniCastReceiver", "cast connect unavailable", e)
            return
        }
        receiver = ctx
        ctx.mediaManager.setMediaLoadCommandCallback(this)
        ctx.mediaManager.setMediaCommandCallback(OmniCastCommandCallback)
        app.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        receiver?.start()
    }

    override fun onStop(owner: LifecycleOwner) {
        receiver?.stop()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // a restored activity holds the intent it was created with, already handled
        if (savedInstanceState == null) onNewIntent(activity.intent)
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    // called by the MainActivity (react's activity does not forward new intents to
    // `addOnNewIntentListener`)
    @JvmStatic
    fun onNewIntent(intent: Intent?) {
        if (intent != null) receiver?.mediaManager?.onNewIntent(intent)
    }

    override fun onLoad(
        senderId: String?,
        request: MediaLoadRequestData,
    ): Task<MediaLoadRequestData> {
        val result = TaskCompletionSource<MediaLoadRequestData>()
        emitLoad(request, result)
        return result.task
    }

    fun addLoadListener(cb: (CastLoadRequest) -> Promise<Promise<Unit>>): () -> Unit {
        runOnMainThread {
            loadListeners.add(cb)
            val (request, result) = pendingLoad ?: return@runOnMainThread
            pendingLoad = null
            emitLoad(request, result)
        }
        return { runOnMainThread { loadListeners.remove(cb) } }
    }

    private fun emitLoad(
        request: MediaLoadRequestData,
        result: TaskCompletionSource<MediaLoadRequestData>,
    ) {
        val queueItem = request.queueData?.let { it.items?.getOrNull(it.startIndex) }
        val info = request.mediaInfo ?: queueItem?.media
        if (info == null) {
            result.trySetException(loadError(MediaError.ERROR_TYPE_INVALID_REQUEST))
            return
        }
        val load = CastLoadRequest(
            castId = info.contentId ?: "",
            customData = info.customData?.let { data ->
                data.keys().asSequence().associateWith { data.optString(it) }
            },
            startTime = request.currentTime.takeIf { it > 0 }?.let { it / 1000.0 }
                ?: queueItem?.startTime?.takeIf { it > 0 },
            autoplay = request.autoplay ?: queueItem?.autoplay ?: true,
        )

        val promises = loadListeners.toList().mapNotNull { listener ->
            try {
                listener(load)
            } catch (_: Throwable) {
                // the listener of a js runtime that is gone (reload)
                loadListeners.remove(listener)
                null
            }
        }
        // nobody listens (yet), hold it until a listener is added
        if (promises.isEmpty()) {
            pendingLoad?.second?.trySetException(loadError(MediaError.ERROR_TYPE_LOAD_CANCELLED))
            pendingLoad = request to result
            return
        }

        // the load succeeds once every listener is done, it fails if one throws.
        val remaining = AtomicInteger(promises.size)
        val failed = { _: Throwable ->
            result.trySetException(loadError(MediaError.ERROR_TYPE_LOAD_FAILED))
            Unit
        }
        for (promise in promises) {
            promise
                .then { loaded ->
                    // https://github.com/margelo/nitro/pull/1678
                    @Suppress("UNCHECKED_CAST")
                    (loaded as Promise<Any?>)
                        .then { if (remaining.decrementAndGet() == 0) result.trySetResult(request) }
                        .catch(failed)
                }
                .catch(failed)
        }
    }

    private fun loadError(type: String) = MediaException(
        MediaError.Builder()
            .setType(type)
            .setReason(MediaError.ERROR_REASON_GENERIC_LOAD_ERROR)
            .build()
    )

    // senders read the playback state from the media session
    fun setSession(session: MediaSession?) {
        val ctx = receiver ?: return
        ctx.mediaManager.setSessionCompatToken(
            session?.let { MediaSessionCompat.Token.fromToken(it.platformToken) }
        )
    }

    // keep senders in sync with what we play (it can change without them: next
    // episode, the user picking something else on the tv...)
    fun broadcastMedia(player: OmniPlayer, item: MediaItem, hasPrev: Boolean, hasNext: Boolean) {
        val manager = receiver?.mediaManager ?: return
        this.player = player
        // the previous media's tracks, ours come with `broadcastTracks` once they are known
        manager.mediaStatusModifier.mediaInfoModifier?.clear()
        manager.mediaStatusModifier.mediaTracksModifier.clear()
        val queue = manager.mediaQueueManager
        // media3 senders cache items by queue item id, a media reported under the
        // previous id would keep showing the previous item there.
        val queueItem = MediaQueueItem.Builder(OmniMediaItemConverter().toMediaQueueItem(item))
            .setItemId(queue.autoGenerateItemId())
            .build()
        manager.setDataFromLoad(MediaLoadRequestData.Builder().setMediaInfo(queueItem.media).build())
        queue.setQueueItems(listOf(queueItem))
        queue.setCurrentItemId(queueItem.itemId)
        queue.notifyQueueFullUpdate()
        manager.mediaStatusModifier
            .setPlayerState(null)
            .setIdleReason(null)
            .setMediaCommandSupported(MediaStatus.COMMAND_QUEUE_PREVIOUS, hasPrev)
            .setMediaCommandSupported(MediaStatus.COMMAND_QUEUE_NEXT, hasNext)
        manager.broadcastMediaStatus()
    }

    // the app stopped what senders see (the player was closed...). an idle media
    // session is reported as FINISHED, senders would then play the next media.
    // must run while the media session is still up: without one it is FINISHED too.
    fun stopMedia(player: OmniPlayer) {
        val manager = receiver?.mediaManager ?: return
        if (player !== this.player) return
        this.player = null
        manager.mediaStatusModifier
            .setPlayerState(MediaStatus.PLAYER_STATE_IDLE)
            .setIdleReason(MediaStatus.IDLE_REASON_CANCELED)
        manager.broadcastMediaStatus()
    }

    // the player of the media senders see
    var player: OmniPlayer? = null
        private set

    // a track's cast id is its position in this list (cast ids start at 1)
    fun tracksOf(player: OmniPlayer) =
        player.audios.map { MediaTrack.TYPE_AUDIO to it } + player.subtitles.map { MediaTrack.TYPE_TEXT to it }

    fun broadcastTracks(player: OmniPlayer) {
        val manager = receiver?.mediaManager ?: return
        if (player !== this.player) return
        val tracks = tracksOf(player)
        val status = manager.mediaStatusModifier
        val info = status.mediaInfoModifier ?: MediaInfoModifier().also { status.setMediaInfoModifier(it) }
        info.setMediaTracks(
            tracks.mapIndexed { i, (type, track) ->
                MediaTrack.Builder(i + 1L, type)
                    .apply {
                        if (type == MediaTrack.TYPE_TEXT) setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        track.label?.let { setName(it) }
                        track.language?.let { setLanguage(it) }
                    }
                    .build()
            }
        )
        status.mediaTracksModifier.setActiveTrackIds(
            tracks.indices.filter { tracks[it].second.selected }.map { it + 1L }.toLongArray()
        )
        manager.broadcastMediaStatus()
    }
}

object OmniCastCommandCallback : MediaCommandCallback() {
    override fun onEditTracksInfo(
        senderId: String?,
        request: EditTracksInfoData,
    ): Task<Void?> {
        val ids = request.activeTrackIds ?: return super.onEditTracksInfo(senderId, request)
        val player = OmniCastReceiver.player ?: return Tasks.forResult(null)
        val active = OmniCastReceiver.tracksOf(player).filterIndexed { i, _ -> i + 1L in ids }
        active.firstOrNull { it.first == MediaTrack.TYPE_AUDIO }?.let { player.selectAudio(it.second) }
        player.selectSubtitle(active.firstOrNull { it.first == MediaTrack.TYPE_TEXT }?.second)
        return Tasks.forResult(null)
    }

    // like the web receiver, we have no queue: prev/next are handled by the app
    override fun onQueueUpdate(
        senderId: String?,
        request: QueueUpdateRequestData,
    ): Task<Void?> {
        val jump = request.jump ?: 0
        val events = OmniPlayer.notificationPlayer?.eventMap
        if (jump < 0) events?.emitPrev()
        if (jump > 0) events?.emitNext()
        return Tasks.forResult(null)
    }
}

@Keep
class OmniCastReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions {
        return CastReceiverOptions.Builder(context)
            .setCustomNamespaces(listOf(OmniPlayer.OMNI_NAMESPACE))
            .build()
    }
}
