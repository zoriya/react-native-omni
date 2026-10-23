package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C.FORMAT_HANDLED
import androidx.media3.common.C.INDEX_UNSET
import androidx.media3.common.C.SELECTION_FLAG_DEFAULT
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Tracks.Group
import androidx.media3.common.VideoSize
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.MediaPlayer.Event
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IMedia.VideoTrack
import org.videolan.libvlc.interfaces.IVLCVout
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// marks a sideloaded sub that is listed but not fetched by vlc yet
private const val PENDING_SLAVE_PREFIX = "vlc-pending-sub:"

private const val SEEK_RETRY_DELAY = 1_000L

// tokens of the work delayed on the vlc thread, `hasMessages(0, token)` tells whether it is under way
private val SEEK = Any()
// playback paused until a sub is fetched
private val SLAVE = Any()
// a sub fetched while paused
private val SLAVE_FETCH = Any()
// vlc stopping the media we left
private val STOPPING = Any()

@SuppressLint("UnsafeOptInUsageError")
class VlcPlayer(ctx: Context) :
    SimpleBasePlayer(Looper.getMainLooper()),
    MediaPlayer.EventListener,
    AudioManager.OnAudioFocusChangeListener {
    private val applicationHandler = Handler(Looper.getMainLooper())

    // libvlc calls block while vlc opens, seeks or flushes its decoders (seconds on tvs), they
    // all run on this thread instead of the ui's
    private val vlcThread = HandlerThread("vlc-commands").apply { start() }
    private val vlcHandler = Handler(vlcThread.looper)
    // libvlc clears its event handler's queue when the listener changes, keep commands out of it
    private val vlcEventHandler = Handler(vlcThread.looper)

    private val libVLC = LibVLC(
        ctx, arrayListOf(
            "--network-caching=1500",
            "--file-caching=1500",
            "--no-stats",
            "--no-osd",
            "--quiet"
        )
    )
    private val player = EventMediaPlayer(libVLC)
    private val vlcVout: IVLCVout = player.vlcVout

    private val audioManager =
        ContextCompat.getSystemService(ctx.applicationContext, AudioManager::class.java)
    // only touched on the vlc thread
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var handleAudioFocus = false

    private var released = false

    private var boundSurfaceView: SurfaceView? = null
    @Volatile
    private var videoOutputStale = false
    // bumped for every output set, a clear reaching vlc late must leave a newer one alone
    @Volatile
    private var videoOutputGeneration = 0

    // vlc's last reported time, or where an unopened media will start
    @Volatile
    private var position = 0L
    // vlc keeps reporting its pre-seek time until it decoded the new position
    @Volatile
    private var seekTarget = TIME_UNSET

    // events vlc sends about a media we left (stopped, switched, ended, failed) until it opens the next one
    private var stale = false
    // a play waiting for vlc to stop the previous media
    private var pendingPlay = false
    // the sideloaded sub picked but not fetched yet
    private var pendingSlave: String? = null
    // hashes of the sideloaded subs vlc was asked to fetch for the current media
    private val askedSlaves = mutableSetOf<String>()

    // only written on the vlc thread
    @Volatile
    private var state = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .add(COMMAND_PLAY_PAUSE)
                .add(COMMAND_PREPARE)
                .add(COMMAND_STOP)
                .add(COMMAND_RELEASE)
                .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_DEFAULT_POSITION)
                .add(COMMAND_SEEK_BACK)
                .add(COMMAND_SEEK_FORWARD)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(COMMAND_SET_SPEED_AND_PITCH)
                .add(COMMAND_GET_VOLUME)
                .add(COMMAND_SET_VOLUME)
                .add(COMMAND_SET_VIDEO_SURFACE)
                .add(COMMAND_SET_MEDIA_ITEM)
                .add(COMMAND_CHANGE_MEDIA_ITEMS)
                .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(COMMAND_GET_METADATA)
                .add(COMMAND_GET_TIMELINE)
                .add(COMMAND_GET_TRACKS)
                .add(COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                .add(COMMAND_GET_AUDIO_ATTRIBUTES)
                .add(COMMAND_SET_AUDIO_ATTRIBUTES)
                .build()
        )
        // progress is polled instead of pushed dozens of times per seconds.
        // this improves perf & battery life (native -> js bridge is expensive)
        .setContentPositionMs { currentPosition() }
        .setContentBufferedPositionMs { currentPosition() }
        .build()

    init {
        player.setEventListener(this, vlcEventHandler)
    }

    override fun getState(): State = state

    private fun currentPosition(): Long = seekTarget.takeIf { it != TIME_UNSET } ?: position

    private val currentItem: MediaItem?
        get() = state.playlist.getOrNull(state.currentMediaItemIndex)?.mediaItem

    private fun vlc(block: () -> Unit): ListenableFuture<*> {
        val done = SettableFuture.create<Unit>()
        vlcHandler.post {
            // an uncaught throw would kill the vlc thread, and the app with it
            try {
                block()
                done.set(Unit)
            } catch (e: Exception) {
                Log.e("VlcPlayer", "vlc command failed", e)
                done.setException(e)
            }
        }
        return done
    }

    private fun postDelayed(token: Any, delayMs: Long, block: Runnable) {
        vlcHandler.postAtTime(block, token, SystemClock.uptimeMillis() + delayMs)
    }

    private fun update(block: State.Builder.() -> Unit) {
        state = state.buildUpon().apply(block).build()
        applicationHandler.post { invalidateState() }
    }

    private fun State.Builder.updateCurrentItem(block: MediaItemData.Builder.() -> Unit): State.Builder =
        setPlaylist(state.playlist.mapIndexed { i, item ->
            if (i == state.currentMediaItemIndex) item.buildUpon().apply(block).build() else item
        })

    private fun updateTracks() {
        update { updateCurrentItem { setTracks(buildTracks()) } }
    }

    private fun State.Builder.setPlaybackStateAndLoading(playbackState: Int): State.Builder =
        setPlaybackState(playbackState).setIsLoading(playbackState == STATE_BUFFERING)

    // vlc doesn't leave idle on its own (prepare/play do), and a seek or a sub fetch keeps it buffering
    private fun State.Builder.setVlcPlaybackState(playbackState: Int): State.Builder {
        if (state.playbackState == STATE_IDLE) return this
        val waiting = vlcHandler.hasMessages(0, SEEK) || vlcHandler.hasMessages(0, SLAVE)
        return setPlaybackStateAndLoading(
            if (playbackState == STATE_READY && waiting) STATE_BUFFERING else playbackState
        )
    }

    override fun onEvent(event: Event) {
        // vlc skips Opening when it never went idle between two medias, it only reports the change
        if (event.type == Event.Opening || event.type == Event.MediaChanged) stale = false
        if (event.type == Event.Stopped) {
            abandonAudioFocus()
            if (vlcHandler.hasMessages(0, STOPPING)) vlcStopped()
        }
        if (stale) return

        when (event.type) {
            Event.Opening -> update {
                setVideoSize(VideoSize.UNKNOWN).setVlcPlaybackState(STATE_BUFFERING)
            }

            Event.Playing -> {
                val durationUs = player.length.takeIf { it > 0 }?.let { it * 1000L } ?: TIME_UNSET
                update {
                    updateCurrentItem { setDurationUs(durationUs).setTracks(buildTracks()) }
                    setVlcPlaybackState(STATE_READY)
                }
                updateVideoSize()
                // vlc reports a media opening paused (see loadMedia) as playing until its first frame
                if (state.playWhenReady && !requestAudioFocus()) pauseForFocusLoss()
            }

            Event.Paused -> when {
                // waiting on a sub is buffering, not a pause
                vlcHandler.hasMessages(0, SLAVE) -> Unit
                // vlc opened the media paused (see loadMedia)
                state.playWhenReady && state.playbackState != STATE_IDLE &&
                    state.playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE -> player.play()
                else -> update { setVlcPlaybackState(STATE_READY) }
            }

            // vlc 4 also sends this for our own stops and media switches, those are stale by now
            Event.EndReached -> {
                leaveMedia()
                update { setVlcPlaybackState(STATE_ENDED) }
            }

            Event.EncounteredError -> {
                leaveMedia()
                val error = PlaybackException(
                    "VLC playback error",
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
                update { setPlayerError(error).setPlaybackStateAndLoading(STATE_IDLE) }
            }

            Event.TimeChanged -> {
                position = event.timeChanged
                if (seekTarget != TIME_UNSET && seekLanded(position, seekTarget)) seekSettled(position)
            }

            // vlc has no video size event, this is the first one where it is known
            Event.PositionChanged -> if (state.videoSize == VideoSize.UNKNOWN) updateVideoSize()

            Event.ESAdded, Event.ESDeleted, Event.ESSelected -> {
                // vlc ignores a slave's `select` flag while another sub plays
                pendingSlave?.let { selectLoadedSlave(it) }
                updateTracks()
                updateVideoSize()
            }

            Event.LengthChanged -> {
                val durationUs = event.lengthChanged.takeIf { it > 0 }?.let { it * 1000L } ?: TIME_UNSET
                update { updateCurrentItem { setDurationUs(durationUs) } }
            }
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (state.playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE) return
                update { setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE) }
                if (state.playWhenReady && !state.timeline.isEmpty) playVlc()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseForFocusLoss()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                update {
                    setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
                }
                player.pause()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null || !handleAudioFocus || hasAudioFocus) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(state.audioAttributes.platformAudioAttributes)
            .setOnAudioFocusChangeListener(this, vlcHandler)
            .build()
        audioFocusRequest = request
        hasAudioFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
        hasAudioFocus = false
    }

    private fun pauseForFocusLoss() {
        update {
            setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
            setPlayWhenReady(false, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        }
        player.pause()
    }

    override fun handleSetAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean
    ): ListenableFuture<*> = vlc {
        this.handleAudioFocus = handleAudioFocus
        if (!handleAudioFocus) abandonAudioFocus()
        val playing = state.playWhenReady && state.playbackState == STATE_READY &&
            state.playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE
        update { setAudioAttributes(audioAttributes) }
        if (playing && !requestAudioFocus()) pauseForFocusLoss()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> = vlc {
        val index = when {
            mediaItems.isEmpty() -> INDEX_UNSET
            startIndex in mediaItems.indices -> startIndex
            else -> 0
        }
        update {
            setPlaylist(mediaItems.map {
                MediaItemData.Builder(Any()).setMediaItem(it).setIsSeekable(true).build()
            })
            setCurrentMediaItemIndex(index)
            // vlc only opens a media by playing it, prepare() does
            setPlaybackStateAndLoading(STATE_IDLE)
            setPlayerError(null)
            setVideoSize(VideoSize.UNKNOWN)
        }
        loadMedia(currentItem)
        // TIME_UNSET is negative
        position = startPositionMs.coerceAtLeast(0L)
    }

    // single media player: the playlist is only ever set or cleared as a whole
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    ): ListenableFuture<*> = handleSetMediaItems(mediaItems, 0, TIME_UNSET)

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> =
        handleSetMediaItems(emptyList(), 0, TIME_UNSET)

    override fun handlePrepare(): ListenableFuture<*> = vlc {
        // opening an open media again would seek it back to its last reported time and unpause it
        if (state.playbackState == STATE_IDLE) openMedia()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = vlc {
        val playbackState = state.playbackState
        update { setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) }
        when {
            !playWhenReady -> player.pause()
            // like prepare(), after a stop or an error
            playbackState == STATE_IDLE -> openMedia()
            playbackState == STATE_ENDED -> {
                position = 0L
                openMedia()
            }
            else -> playVlc()
        }
    }

    override fun handleStop(): ListenableFuture<*> = vlc {
        // prepare/play opens it again where it was
        position = currentPosition()
        leaveMedia()
        stopVlc()
        update { setPlaybackStateAndLoading(STATE_IDLE) }
    }

    override fun handleRelease(): ListenableFuture<*> {
        handleClearVideoOutput(null)
        released = true
        vlcHandler.post {
            vlcHandler.removeCallbacksAndMessages(null)
            abandonAudioFocus()
            player.setEventListener(null)
            player.stop()
            player.release()
            libVLC.release()
            vlcThread.quitSafely()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = vlc {
        if (state.timeline.isEmpty) return@vlc
        val target = positionMs.coerceAtLeast(0L)

        if (mediaItemIndex != state.currentMediaItemIndex && mediaItemIndex in state.playlist.indices) {
            // an open player opens the new item, an idle one waits for prepare/play
            val open = state.playbackState != STATE_IDLE
            update {
                setCurrentMediaItemIndex(mediaItemIndex)
                setPlaybackStateAndLoading(if (open) STATE_BUFFERING else STATE_IDLE)
                setPlayerError(null)
                setVideoSize(VideoSize.UNKNOWN)
            }
            loadMedia(currentItem)
            position = target
            if (open) openMedia()
            return@vlc
        }

        when (state.playbackState) {
            STATE_IDLE -> position = target
            // like exoplayer, going back after the end plays from there if it was still playing
            STATE_ENDED -> {
                position = target
                if (state.playWhenReady) openMedia()
                else update { setPlaybackStateAndLoading(STATE_IDLE) }
            }
            else -> {
                // a live stream
                if (vlcStarted() && !player.isSeekable) return@vlc
                seekVlc(target)
                update { setVlcPlaybackState(STATE_BUFFERING) }
            }
        }
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = vlc {
        val rate = playbackParameters.speed.coerceAtLeast(0.01f)
        update { setPlaybackParameters(PlaybackParameters(rate)) }
        player.rate = rate
    }

    override fun handleSetVolume(volume: Float, volumeOperationType: Int): ListenableFuture<*> = vlc {
        val clamped = volume.coerceIn(0f, 1f)
        update { setVolume(clamped) }
        player.volume = (clamped * 100).toInt()
    }

    // drops what was under way for the media vlc leaves (stop, switch, end, error)
    private fun leaveMedia() {
        cancelSeek()
        vlcHandler.removeCallbacksAndMessages(SLAVE)
        vlcHandler.removeCallbacksAndMessages(SLAVE_FETCH)
        stale = true
    }

    // vlc stops on its own threads. a play reaching it before it is done has it keep reporting the
    // playing state across both medias (it never says the next one opens nor plays), so it waits.
    private fun stopVlc() {
        val running = player.playerState.let {
            it == IMedia.State.Opening || it == IMedia.State.Playing || it == IMedia.State.Paused
        }
        pendingPlay = false
        player.stop()
        // a previous stop may still be under way
        if (!running) return
        vlcHandler.removeCallbacksAndMessages(STOPPING)
        postDelayed(STOPPING, 2_000) { vlcStopped() }
    }

    private fun vlcStopped() {
        vlcHandler.removeCallbacksAndMessages(STOPPING)
        if (!pendingPlay) return
        pendingPlay = false
        playVlc()
    }

    private fun playVlc() {
        // resumes once the sub is in
        if (vlcHandler.hasMessages(0, SLAVE)) return
        if (vlcHandler.hasMessages(0, STOPPING)) {
            pendingPlay = true
            return
        }
        player.play()
    }

    // vlc's input is up, it knows whether the media is seekable
    private fun vlcStarted(): Boolean =
        !vlcHandler.hasMessages(0, STOPPING) &&
            player.playerState.let { it == IMedia.State.Playing || it == IMedia.State.Paused }

    private fun loadMedia(item: MediaItem?) {
        leaveMedia()
        trackOrders.clear()
        pendingSlave = null
        askedSlaves.clear()
        videoOutputStale = false
        // a media set while vlc plays starts on its own once the previous one stopped
        stopVlc()
        val uri = item?.localConfiguration?.uri?.toString()
        if (!uri.isNullOrEmpty()) {
            val media = Media(libVLC, uri.toUri())
            media.setHWDecoderEnabled(true, false)
            applyRequestHeaders(media, item.requestMetadata.extras)
            // vlc only opens a media by playing it, this has it stop on the first frame. vlc creates
            // the input on setMedia, so whether to play can't be decided later (see onEvent's Paused)
            media.addOption(":start-paused")
            player.setMedia(media)
            media.release()
        }
        updateTracks()
    }

    // vlc doesn't allow arbitrary headers :c
    private fun applyRequestHeaders(media: Media, extras: Bundle?) {
        if (extras == null) return
        for (name in extras.keySet()) {
            val value = extras.getString(name) ?: continue
            when (name.lowercase()) {
                "user-agent" -> media.addOption(":http-user-agent=$value")
                "referer", "referrer" -> media.addOption(":http-referrer=$value")
            }
        }
    }

    // vlc's `:start-time` trims the media instead of starting further in, so seek there once it can
    private fun openMedia() {
        if (state.timeline.isEmpty) return
        update { setPlayerError(null).setPlaybackStateAndLoading(STATE_BUFFERING) }
        playVlc()
        if (position > 0L) seekVlc(position)
    }

    private fun resume() {
        if (state.playWhenReady) playVlc() else update { setVlcPlaybackState(STATE_READY) }
    }

    private fun seekVlc(target: Long) {
        // in a burst of seeks (double tapping to skip a minute) vlc would restart its demuxer for
        // each one: leave the one under way alone, the poll asks for wherever the burst ended up
        val burst = vlcHandler.hasMessages(0, SEEK)
        vlcHandler.removeCallbacksAndMessages(SEEK)
        seekTarget = target
        var asked = !burst && player.isSeekable
        if (asked) player.time = target
        var attempts = 0
        var lastPoll = player.time.coerceAtLeast(0L)

        // vlc silently drops a seek asked for while it handles another, but asking again while it
        // handles ours restarts it. its clock tells which: frozen while seeking, ticking once dropped
        val poll = object : Runnable {
            override fun run() {
                // vlc only seeks once its input is up. one up and still not seekable is a live stream
                if (!player.isSeekable) {
                    if (vlcStarted() && attempts++ >= 10) return seekSettled(player.time.coerceAtLeast(0L))
                    postDelayed(SEEK, 100, this)
                    return
                }
                val time = player.time.coerceAtLeast(0L)
                if (!asked) {
                    player.time = target
                    asked = true
                    attempts = 0
                    lastPoll = time
                    postDelayed(SEEK, SEEK_RETRY_DELAY, this)
                    return
                }
                val ticking = time - lastPoll in (SEEK_RETRY_DELAY / 2)..(SEEK_RETRY_DELAY * 2)
                lastPoll = time
                // a target vlc never reaches must not mask the position forever
                if (seekLanded(time, target) || attempts++ >= 10) return seekSettled(time)
                if (ticking) player.time = target
                postDelayed(SEEK, SEEK_RETRY_DELAY, this)
            }
        }
        postDelayed(SEEK, if (asked || burst) SEEK_RETRY_DELAY else 100L, poll)
    }

    private fun seekLanded(time: Long, target: Long): Boolean = abs(time - target) < 1_000

    private fun cancelSeek() {
        vlcHandler.removeCallbacksAndMessages(SEEK)
        seekTarget = TIME_UNSET
    }

    private fun seekSettled(time: Long) {
        cancelSeek()
        position = time
        update { setVlcPlaybackState(STATE_READY) }
    }

    private fun selectTextTrack(trackId: String) {
        val slaveUri = trackId.substringAfter(PENDING_SLAVE_PREFIX, "")
        if (slaveUri.isEmpty()) {
            dropPendingSlave()
            player.selectTrack(trackId)
            return
        }

        // a selection kept from a previous media must not sideload its sub in this one
        val subtitleConfigs = currentItem?.localConfiguration?.subtitleConfigurations.orEmpty()
        if (subtitleConfigs.none { it.uri.toString() == slaveUri }) return
        if (selectLoadedSlave(slaveUri)) return

        pendingSlave = slaveUri
        val hash = md5(slaveUri)
        if (!askedSlaves.add(hash)) {
            updateTracks()
            return
        }

        // vlc fetches subs on its input thread, a pause queued behind the fetch would only land once it is over
        val blocking = player.isPlaying
        // a sub that never loads (dead url) must not leave playback paused, nor show as selected, forever
        postDelayed(if (blocking) SLAVE else SLAVE_FETCH, 15_000) {
            if (pendingSlave == slaveUri) pendingSlave = null
            if (loadedSlave(hash) == null) askedSlaves.remove(hash)
            if (blocking) resume()
            updateTracks()
        }
        if (blocking) {
            player.pause()
            update { setVlcPlaybackState(STATE_BUFFERING) }
        }
        player.addSlave(IMedia.Slave.Type.Subtitle, slaveUri.toUri(), true)
        updateTracks()
    }

    private fun selectLoadedSlave(uri: String): Boolean {
        val es = loadedSlave(md5(uri)) ?: return false
        player.selectTrack(es.id)
        dropPendingSlave()
        return true
    }

    // vlc keys a slave's tracks by the md5 of its uri ("<md5(uri)>/spu/<n>")
    private fun loadedSlave(hash: String): IMedia.Track? =
        player.getTracks(IMedia.Track.Type.Text).orEmpty().firstOrNull { it.id.startsWith("$hash/spu/") }

    private fun dropPendingSlave() {
        pendingSlave = null
        if (!vlcHandler.hasMessages(0, SLAVE)) return
        vlcHandler.removeCallbacksAndMessages(SLAVE)
        resume()
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // the adaptive demuxer has no stable es id so its tracks are named `audio/auto/<n>` (see
    // EsOutCreateStrId in vlc's es_out.c) while a plain file gives `audio/<n>`.
    // for hls it sets the description to "<EXT-X-MEDIA GROUP-ID> <NAME>" (hls/playlist/Parser.cpp),
    // so one track offered in n quality groups shows up as n tracks: keep the part after the
    // group-id so they collapse back into a single track holding n renditions.
    // (dash sets the description to the Role, a single word, so this is a no-op there.)
    private fun trackName(track: IMedia.Track): String? {
        val description = track.description
        if (description == null || !track.id.contains("/auto/")) return track.name
        return description.substringAfter(' ')
    }

    // tracks sharing a key are renditions of one logical track, only adaptive streams have some.
    // they are keyed on the name alone: once vlc opens the rendition it actually reads, it adds one
    // more es for it, described by the segments themselves instead of the playlist - no group-id to
    // strip, and the container's language (`chi`) rather than the playlist's (`zh`).
    // media3's own hls source groups renditions by name alone as well.
    private fun trackKey(track: IMedia.Track): String =
        if (track.id.contains("/auto/")) trackName(track) ?: track.id
        else track.id

    // selecting a track makes vlc recreate its es with a fresh, higher id ("audio/auto/4"
    // becomes "audio/auto/7"), so its own order puts whatever was picked last and the list
    // reshuffles. order on the lowest es number a track was ever seen with instead.
    private val trackOrders = mutableMapOf<Pair<Int, String>, Int>()

    private fun trackOrder(type: Int, tracks: List<IMedia.Track>): Int {
        val key = type to trackKey(tracks.first())
        val es = tracks.minOf { it.id.substringAfterLast('/').toIntOrNull() ?: Int.MAX_VALUE }
        val order = minOf(trackOrders[key] ?: Int.MAX_VALUE, es)
        trackOrders[key] = order
        return order
    }

    private fun buildTracks(): Tracks {
        val result = ArrayList<Group>()
        val selectedVideo = player.getSelectedTrack(IMedia.Track.Type.Video)
        val selectedAudio = player.getSelectedTrack(IMedia.Track.Type.Audio)
        val subtitleConfigs = currentItem?.localConfiguration?.subtitleConfigurations.orEmpty()
        val textTracks = player.getTracks(IMedia.Track.Type.Text).orEmpty()
        val subtitleSlavesByHash = subtitleConfigs.associateBy { md5(it.uri.toString()) }
        val loadedSlaves = textTracks.mapNotNull { track ->
            subtitleSlavesByHash[track.id.substringBefore("/spu/", "")]?.let { it.uri to track }
        }.toMap()
        // the sub being fetched is the selected one, vlc still has the previous es
        val pendingSlaveUri = pendingSlave?.toUri()?.takeIf { it !in loadedSlaves }
        val selectedSubtitle =
            if (pendingSlaveUri != null) null else player.getSelectedTrack(IMedia.Track.Type.Text)

        player.getTracks(IMedia.Track.Type.Video).orEmpty()
            .groupBy { trackKey(it) }
            .entries.sortedBy { trackOrder(TRACK_TYPE_VIDEO, it.value) }
            .forEach { (_, videoTracks) ->
                val videoFormats = videoTracks.map { track ->
                    val videoTrack = track as? VideoTrack
                    Format.Builder()
                        .setId(track.id)
                        .setLabel(trackName(track))
                        .setLanguage(track.language)
                        .setSampleMimeType("video/x-unknown")
                        .setWidth(videoTrack?.width?.takeIf { it > 0 } ?: Format.NO_VALUE)
                        .setHeight(videoTrack?.height?.takeIf { it > 0 } ?: Format.NO_VALUE)
                        .setFrameRate(
                            videoTrack?.takeIf { it.frameRateDen > 0 && it.frameRateNum > 0 }
                                ?.let { it.frameRateNum.toFloat() / it.frameRateDen }
                                ?: Format.NO_VALUE.toFloat()
                        )
                        .setAverageBitrate(track.bitrate.takeIf { it > 0 } ?: Format.NO_VALUE)
                        .build()
                }
                val group = TrackGroup(
                    "vlc-video-${videoTracks.minOf { it.id }}",
                    *videoFormats.toTypedArray()
                )
                val selected = BooleanArray(videoFormats.size) { idx ->
                    selectedVideo != null && selectedVideo.id == videoTracks[idx].id
                }
                val support = IntArray(videoFormats.size) { FORMAT_HANDLED }
                result.add(Group(group, videoFormats.size > 1, support, selected))
            }

        player.getTracks(IMedia.Track.Type.Audio).orEmpty()
            .groupBy { trackKey(it) }
            .entries.sortedBy { trackOrder(TRACK_TYPE_AUDIO, it.value) }
            .forEach { (_, audioTracks) ->
                val audioFormats = audioTracks.map { track ->
                    Format.Builder()
                        .setId(track.id)
                        .setLabel(trackName(track))
                        .setLanguage(track.language)
                        .setSampleMimeType("audio/x-unknown")
                        .build()
                }
                val group = TrackGroup(
                    "vlc-audio-${audioTracks.minOf { it.id }}",
                    *audioFormats.toTypedArray()
                )
                val selected = BooleanArray(audioFormats.size) { idx ->
                    selectedAudio != null && selectedAudio.id == audioTracks[idx].id
                }
                val support = IntArray(audioFormats.size) { FORMAT_HANDLED }
                result.add(Group(group, audioFormats.size > 1, support, selected))
            }

        textTracks
            .filter { it !in loadedSlaves.values }
            .sortedBy { trackOrder(TRACK_TYPE_TEXT, listOf(it)) }
            .forEach { track ->
                val format = Format.Builder()
                    .setId(track.id)
                    .setLabel(track.name)
                    .setLanguage(track.language)
                    .setSampleMimeType("text/x-unknown")
                    .build()
                val group = TrackGroup("vlc-sub-${track.id}", format)
                val selected = booleanArrayOf(selectedSubtitle?.id == track.id)
                result.add(Group(group, false, intArrayOf(FORMAT_HANDLED), selected))
            }

        // sideloaded subs, listed from their config whether or not vlc fetched them yet
        subtitleConfigs.forEach { config ->
            val track = loadedSlaves[config.uri]
            val format = Format.Builder()
                .setId("$PENDING_SLAVE_PREFIX${config.uri}")
                .setLabel(config.label ?: track?.name)
                .setLanguage(config.language ?: track?.language)
                .setSampleMimeType("text/x-unknown")
                .build()
            val group = TrackGroup(config.id ?: "vlc-sub-${config.uri}", format)
            val selected = booleanArrayOf(
                config.uri == pendingSlaveUri || (track != null && selectedSubtitle?.id == track.id)
            )
            result.add(Group(group, false, intArrayOf(FORMAT_HANDLED), selected))
        }

        return if (result.isEmpty()) Tracks.EMPTY else Tracks(result)
    }

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters
    ): ListenableFuture<*> = vlc {
        update { setTrackSelectionParameters(trackSelectionParameters) }
        val videoDisabled = trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_VIDEO)
        val audioDisabled = trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_AUDIO)
        val textDisabled = trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_TEXT)

        if (videoDisabled) player.setVideoTrackEnabled(false)
        if (audioDisabled) player.unselectTrackType(IMedia.Track.Type.Audio)
        if (textDisabled) {
            dropPendingSlave()
            player.unselectTrackType(IMedia.Track.Type.Text)
        }

        val tracks = buildTracks()

        var hasVideoOverride = false
        var hasAudioOverride = false
        var hasTextOverride = false

        for (override in trackSelectionParameters.overrides.values) {
            val selectedIndex = override.trackIndices.firstOrNull() ?: continue
            if (selectedIndex !in 0 until override.mediaTrackGroup.length) continue
            val trackId = override.mediaTrackGroup.getFormat(selectedIndex).id ?: continue

            when (override.type) {
                TRACK_TYPE_VIDEO -> {
                    hasVideoOverride = true
                    player.setVideoTrackEnabled(true)
                    player.selectTrack(trackId)
                }

                TRACK_TYPE_AUDIO -> {
                    hasAudioOverride = true
                    player.selectTrack(trackId)
                }

                TRACK_TYPE_TEXT -> {
                    hasTextOverride = true
                    selectTextTrack(trackId)
                }
            }
        }

        if (!videoDisabled && !hasVideoOverride) {
            val videoPreference = selectTrackByPreference(
                tracks, TRACK_TYPE_VIDEO,
                trackSelectionParameters.preferredVideoLanguages,
                trackSelectionParameters.preferredVideoLabels,
            )
            player.setVideoTrackEnabled(true)
            videoPreference?.let { player.selectTrack(it) }
        }
        if (!audioDisabled && !hasAudioOverride) {
            val audioPreference = selectTrackByPreference(
                tracks, TRACK_TYPE_AUDIO,
                trackSelectionParameters.preferredAudioLanguages,
                trackSelectionParameters.preferredAudioLabels,
            )
            audioPreference?.let { player.selectTrack(it) }
        }
        if (!textDisabled && !hasTextOverride) {
            val textPreference = selectTrackByPreference(
                tracks, TRACK_TYPE_TEXT,
                trackSelectionParameters.preferredTextLanguages,
                trackSelectionParameters.preferredTextLabels,
            )
            textPreference?.let { selectTextTrack(it) }
        }
    }

    private fun selectTrackByPreference(
        tracks: Tracks,
        trackType: Int,
        languages: List<String>,
        labels: List<String>,
    ): String? {
        val preferredLanguages = languages.filter { it.isNotEmpty() }
        val preferredLabels = labels.filter { it.isNotEmpty() }
        if (preferredLanguages.isEmpty() && preferredLabels.isEmpty()) return null

        data class Candidate(
            val id: String,
            val langIndex: Int,
            val labelIndex: Int,
            val hasDefaultFlag: Boolean
        )

        val candidates = tracks.groups
            .filter { it.type == trackType }
            .flatMap { group ->
                (0 until group.length).mapNotNull { i ->
                    val format = group.getTrackFormat(i)
                    val id = format.id ?: return@mapNotNull null

                    val langIndex = format.language?.let { lang ->
                        preferredLanguages.indexOfFirst { it.equals(lang, ignoreCase = true) }
                    } ?: -1
                    val labelIndex = format.label?.let { label ->
                        preferredLabels.indexOfFirst { it.equals(label, ignoreCase = true) }
                    } ?: -1

                    val matchesLang = preferredLanguages.isEmpty() || langIndex >= 0
                    val matchesLabel = preferredLabels.isEmpty() || labelIndex >= 0
                    if (matchesLang && matchesLabel) {
                        Candidate(
                            id,
                            if (langIndex >= 0) langIndex else Int.MAX_VALUE,
                            if (labelIndex >= 0) labelIndex else Int.MAX_VALUE,
                            (format.selectionFlags and SELECTION_FLAG_DEFAULT) != 0
                        )
                    } else null
                }
            }

        return candidates.minWithOrNull(
            compareBy<Candidate> { it.langIndex }
                .thenBy { it.labelIndex }
                .thenByDescending { it.hasDefaultFlag }
        )?.id
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is SurfaceView -> {
                if (vlcVout.areViewsAttached() && boundSurfaceView === videoOutput) {
                    return Futures.immediateVoidFuture()
                }
                boundSurfaceView = videoOutput
                videoOutputGeneration++
                // clearing the previous output turned the video track off, only a rebuild turns it back on
                videoOutputStale = state.playbackState.let { it == STATE_READY || it == STATE_BUFFERING }
                vlcVout.setVideoView(videoOutput)
                vlcVout.attachViews()
            }

            is SurfaceHolder -> {
                val surface = videoOutput.surface
                if (surface != null && surface.isValid && !vlcVout.areViewsAttached()) {
                    videoOutputGeneration++
                    vlcVout.setVideoSurface(surface, videoOutput)
                    vlcVout.attachViews()
                }
            }

            is Surface -> {
                videoOutputGeneration++
                vlcVout.setVideoSurface(videoOutput, null)
                vlcVout.attachViews()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput is SurfaceView && boundSurfaceView !== videoOutput) {
            return Futures.immediateVoidFuture()
        }
        boundSurfaceView = null
        // the surface is gone once surfaceDestroyed returns, vlc has to be done with it by then.
        // libvlc can be stuck for seconds, so this only waits on its thread for so long.
        if (!released && vlcVout.areViewsAttached()) {
            val deadline = SystemClock.uptimeMillis() + 250
            val done = CountDownLatch(1)
            val generation = videoOutputGeneration
            vlcHandler.post {
                if (generation == videoOutputGeneration) {
                    player.setVideoTrackEnabled(false)
                    while (player.getSelectedTrack(IMedia.Track.Type.Video) != null &&
                        SystemClock.uptimeMillis() < deadline
                    ) {
                        Thread.sleep(2)
                    }
                }
                done.countDown()
            }
            done.await(250, TimeUnit.MILLISECONDS)
        }
        vlcVout.detachViews()
        return Futures.immediateVoidFuture()
    }

    fun rebuildVideoOutput() {
        if (released) return
        val open = playbackState == STATE_READY || playbackState == STATE_BUFFERING
        if (!videoOutputStale || !playWhenReady || !open || !vlcVout.areViewsAttached()) return
        videoOutputStale = false
        vlcHandler.post {
            player.setVideoTrackEnabled(false)
            player.setVideoTrackEnabled(true)
            player.updateVideoSurfaces()
        }
    }

    fun updateVideoLayout(width: Int, height: Int) {
        if (released || !vlcVout.areViewsAttached()) return
        if (width > 0 && height > 0) vlcVout.setWindowSize(width, height)
        vlcHandler.post { player.updateVideoSurfaces() }
    }

    // vlc has no video size event, OmniView needs it to size the content frame (and the pip
    // aspect ratio) to the video instead of the SurfaceView's bounds
    private fun updateVideoSize() {
        val videoTrack = player.getSelectedTrack(IMedia.Track.Type.Video) as? VideoTrack ?: return
        if (videoTrack.width <= 0 || videoTrack.height <= 0) return
        val par = if (videoTrack.sarNum > 0 && videoTrack.sarDen > 0) {
            videoTrack.sarNum.toFloat() / videoTrack.sarDen
        } else {
            1f
        }
        val size = VideoSize(videoTrack.width, videoTrack.height, par)
        if (size != state.videoSize) update { setVideoSize(size) }
    }
}

// libvlc only lets subclasses pick the thread its events are handled on
private class EventMediaPlayer(libVLC: LibVLC) : MediaPlayer(libVLC) {
    fun setEventListener(listener: MediaPlayer.EventListener, handler: Handler) =
        super.setEventListener(listener, handler)
}
