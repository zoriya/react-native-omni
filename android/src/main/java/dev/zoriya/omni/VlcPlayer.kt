package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C.FORMAT_HANDLED
import androidx.media3.common.C.INDEX_UNSET
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

// marks a sideloaded sub, listed from its config whether or not vlc fetched it yet
private const val SLAVE_PREFIX = "vlc-sub:"

private const val SEEK_RETRY_DELAY = 1_000L

// tokens of the work delayed on the vlc thread, `hasMessages(0, token)` tells whether it is under way
private val SEEK = Any()
// a sub being fetched
private val SLAVE = Any()
// vlc stopping the media we left
private val STOPPING = Any()
// the video track turning back on for a new output
private val REBUILD = Any()

@SuppressLint("UnsafeOptInUsageError")
class VlcPlayer(ctx: Context) :
    SimpleBasePlayer(Looper.getMainLooper()),
    MediaPlayer.EventListener {
    private val applicationHandler = Handler(Looper.getMainLooper())

    // libvlc calls block while vlc opens, seeks or flushes its decoders (seconds on tvs), they
    // all run on this thread instead of the ui's
    private val vlcThread = HandlerThread("vlc-commands").apply { start() }
    private val vlcHandler = VlcHandler(vlcThread.looper)
    // libvlc clears its event handler's queue when the listener changes, keep commands out of it
    private val vlcEventHandler = VlcHandler(vlcThread.looper)

    private val libVLC = LibVLC(
        ctx, arrayListOf(
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
    // posted rather than handed vlcHandler: the audio manager would wrap it in a handler of its own.
    // a single one: the audio manager tells requests apart by their listener
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { vlcHandler.post { onAudioFocusChange(it) } }

    private var boundOutput: View? = null
    @Volatile
    private var videoOutputStale = false
    // bumped for every output set, a clear reaching vlc late must leave a newer one alone
    @Volatile
    private var videoOutputGeneration = 0
    // a clear waiting for vlc to let go of the output
    @Volatile
    private var videoReleased: CountDownLatch? = null

    // vlc's last reported time, or where an unopened media will start
    @Volatile
    private var position = 0L
    // vlc keeps reporting its pre-seek time until it decoded the new position
    @Volatile
    private var seekTarget = TIME_UNSET
    // the target vlc was asked for, TIME_UNSET while seekTarget waits for vlc to be able to seek
    private var seekSent = TIME_UNSET

    // events vlc sends about a media we left (stopped, switched, ended, failed) until it opens the next one
    private var stale = false
    // a play waiting for vlc to stop the previous media
    private var pendingPlay = false
    // the sideloaded sub picked but not fetched yet
    private var pendingSlave: String? = null
    // playback waits for the sub to be fetched, it is buffering rather than paused
    private var slavePaused = false
    // hashes of the sideloaded subs vlc was asked to fetch for the current media
    private val askedSlaves = mutableSetOf<String>()
    // the track selection parameters were applied to the opened media, they wait for vlc to list its tracks
    private var tracksSelected = false

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
            try {
                block()
            } finally {
                done.set(Unit)
            }
        }
        return done
    }

    private fun postDelayed(token: Any, delayMs: Long, block: Runnable) {
        vlcHandler.postAtTime(block, token, SystemClock.uptimeMillis() + delayMs)
    }

    private fun update(block: State.Builder.() -> Unit) {
        val wasOpen = state.playbackState == STATE_BUFFERING || state.playbackState == STATE_READY
        state = state.buildUpon().apply(block).build()
        // like exoplayer, the focus is kept while paused but not once there is nothing left to play
        if (wasOpen && (state.playbackState == STATE_IDLE || state.playbackState == STATE_ENDED)) abandonAudioFocus()
        applicationHandler.post { invalidateState() }
    }

    private fun State.Builder.updateCurrentItem(block: MediaItemData.Builder.() -> Unit): State.Builder =
        setPlaylist(state.playlist.mapIndexed { i, item ->
            if (i == state.currentMediaItemIndex) item.buildUpon().apply(block).build() else item
        })

    private fun updateTracks() {
        update { updateCurrentItem { setTracks(buildTracks()) } }
    }

    // vlc doesn't leave idle on its own (prepare/play do), and a seek or a sub fetch keeps it buffering
    private fun State.Builder.setVlcPlaybackState(playbackState: Int): State.Builder {
        if (state.playbackState == STATE_IDLE) return this
        val waiting = seekTarget != TIME_UNSET || slavePaused
        return setPlaybackState(if (playbackState == STATE_READY && waiting) STATE_BUFFERING else playbackState)
    }

    private fun durationUs(lengthMs: Long): Long = if (lengthMs > 0) lengthMs * 1000L else TIME_UNSET

    override fun onEvent(event: Event) {
        // vlc skips Opening when it never went idle between two medias, it only reports the change
        if (event.type == Event.Opening || event.type == Event.MediaChanged) stale = false
        if (event.type == Event.Stopped && vlcHandler.hasMessages(0, STOPPING)) vlcStopped()
        if (videoReleased != null &&
            (event.type == Event.ESSelected || event.type == Event.ESDeleted) &&
            player.getSelectedTrack(IMedia.Track.Type.Video) == null
        ) {
            videoReleased?.countDown()
            videoReleased = null
        }
        if (stale) return

        when (event.type) {
            Event.Opening -> update {
                setVideoSize(VideoSize.UNKNOWN).setVlcPlaybackState(STATE_BUFFERING)
            }

            Event.Playing -> {
                update {
                    updateCurrentItem {
                        setDurationUs(durationUs(player.length))
                            .setIsSeekable(player.isSeekable)
                            .setTracks(buildTracks())
                    }
                    setVlcPlaybackState(STATE_READY)
                }
                updateVideoSize()
                if (videoOutputStale) rebuildVideoOutput()
                if (seekTarget != TIME_UNSET && seekSent == TIME_UNSET) sendSeek()
                if (!tracksSelected) {
                    tracksSelected = true
                    selectTracks(state.trackSelectionParameters)
                }
                // vlc reports a media opening paused (see loadMedia) as playing until its first frame
                if (state.playWhenReady && !requestAudioFocus()) pauseForFocusLoss()
            }

            Event.Paused -> when {
                // waiting on a sub is buffering, not a pause
                slavePaused -> Unit
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
                update { setPlayerError(error).setPlaybackState(STATE_IDLE) }
            }

            Event.TimeChanged -> {
                position = event.timeChanged
                if (seekSent != TIME_UNSET && seekLanded(position, seekSent)) seekDone(position)
            }

            // vlc has no video size event, this is the first one where it is known
            Event.PositionChanged -> if (state.videoSize == VideoSize.UNKNOWN) updateVideoSize()

            Event.ESAdded, Event.ESDeleted, Event.ESSelected -> {
                // vlc ignores a slave's `select` flag while another sub plays
                pendingSlave?.let { selectLoadedSlave(it) }
                updateTracks()
                updateVideoSize()
            }

            Event.LengthChanged -> update { updateCurrentItem { setDurationUs(durationUs(event.lengthChanged)) } }

            Event.SeekableChanged -> {
                update { updateCurrentItem { setIsSeekable(event.seekable) } }
                if (event.seekable && seekTarget != TIME_UNSET && seekSent == TIME_UNSET) sendSeek()
            }
        }
    }

    private fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (state.playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE) return
                update { setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE) }
                val open = state.playbackState == STATE_READY || state.playbackState == STATE_BUFFERING
                if (state.playWhenReady && open) playVlc()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                abandonAudioFocus()
                pauseForFocusLoss()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // a play asked for meanwhile takes it back
                hasAudioFocus = false
                update { setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) }
                player.pause()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null || !handleAudioFocus || hasAudioFocus) return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(state.audioAttributes.platformAudioAttributes)
            .setOnAudioFocusChangeListener(audioFocusListener)
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
        // an open player opens the new media, an idle one waits for prepare/play
        val playbackState = when {
            state.playbackState == STATE_IDLE -> STATE_IDLE
            mediaItems.isEmpty() -> STATE_ENDED
            else -> STATE_BUFFERING
        }
        update {
            setPlaylist(mediaItems.map { MediaItemData.Builder(Any()).setMediaItem(it).setIsSeekable(true).build() })
            setCurrentMediaItemIndex(if (mediaItems.isEmpty()) INDEX_UNSET else startIndex.coerceAtLeast(0))
            setPlaybackState(playbackState)
            setPlayerError(null)
            setVideoSize(VideoSize.UNKNOWN)
        }
        loadMedia(currentItem)
        // TIME_UNSET is negative
        position = startPositionMs.coerceAtLeast(0L)
        if (playbackState == STATE_BUFFERING) openMedia()
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
        // asked before vlc plays anything, this takes it back from a transient loss as well
        if (playWhenReady && !state.timeline.isEmpty && !requestAudioFocus()) return@vlc pauseForFocusLoss()
        update {
            setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            if (playWhenReady) setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
        }
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
        update { setPlaybackState(STATE_IDLE) }
    }

    override fun handleRelease(): ListenableFuture<*> {
        handleClearVideoOutput(null)
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
        when (state.playbackState) {
            STATE_IDLE -> position = target
            // like exoplayer, going back after the end plays from there if it was still playing
            STATE_ENDED -> {
                position = target
                if (state.playWhenReady) openMedia()
                else update { setPlaybackState(STATE_IDLE) }
            }
            else -> {
                // a live stream
                if (!state.playlist[state.currentMediaItemIndex].isSeekable) return@vlc
                seekVlc(target)
                update { setVlcPlaybackState(STATE_BUFFERING) }
            }
        }
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> = vlc {
        // vlc can't keep the pitch apart from the speed
        update { setPlaybackParameters(PlaybackParameters(playbackParameters.speed)) }
        player.rate = playbackParameters.speed
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
        slavePaused = false
        stale = true
        // vlc opens it again with its default tracks
        tracksSelected = false
    }

    // vlc stops on its own threads. a play reaching it before it is done has it keep reporting the
    // playing state across both medias (it never says the next one opens nor plays), so it waits.
    // vlc 4 reports its stopping state, which follows the end of a media too, as `Ended`.
    private fun stopVlc() {
        val running = player.playerState.let {
            it == IMedia.State.Opening || it == IMedia.State.Playing || it == IMedia.State.Paused ||
                it == IMedia.State.Ended
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
        if (slavePaused) return
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
        update { setPlayerError(null).setPlaybackState(STATE_BUFFERING) }
        playVlc()
        if (position > 0L) seekVlc(position)
    }

    private fun resume() {
        if (state.playWhenReady) playVlc() else update { setVlcPlaybackState(STATE_READY) }
    }

    private fun seekVlc(target: Long) {
        seekTarget = target
        // in a burst of seeks (double tapping to skip a minute) vlc would restart its demuxer for
        // each one, and ignores those asked for while it handles another: the last one waits for
        // the one under way to land (see seekDone)
        if (seekSent == TIME_UNSET) sendSeek()
    }

    // vlc only seeks once its input is up, Playing and SeekableChanged send it then
    private fun sendSeek() {
        val time = player.time.coerceAtLeast(0L)
        if (!vlcHandler.hasMessages(0, STOPPING) && player.isSeekable) {
            seekSent = seekTarget
            player.time = seekTarget
        }
        vlcHandler.removeCallbacksAndMessages(SEEK)
        watchSeek(time, 0)
    }

    // vlc reports neither a seek done nor one dropped, only its clock tells: frozen while it seeks,
    // ticking once it dropped it. asking again while it handles one would restart it.
    private fun watchSeek(lastPoll: Long, attempts: Int) {
        postDelayed(SEEK, SEEK_RETRY_DELAY) {
            val time = player.time.coerceAtLeast(0L)
            when {
                seekSent == TIME_UNSET -> when {
                    !vlcStarted() -> watchSeek(time, attempts)
                    player.isSeekable -> sendSeek()
                    // its input is up and still not seekable, a live stream
                    else -> seekSettled(time)
                }
                // TimeChanged usually sees it first
                seekLanded(time, seekSent) -> seekDone(time)
                // a target vlc never reaches must not mask the position forever
                attempts >= 10 -> seekSettled(time)
                else -> {
                    if (time - lastPoll in (SEEK_RETRY_DELAY / 2)..(SEEK_RETRY_DELAY * 2)) player.time = seekSent
                    watchSeek(time, attempts + 1)
                }
            }
        }
    }

    private fun seekLanded(time: Long, target: Long): Boolean = abs(time - target) < 1_000

    // the seek under way landed, a newer one of the burst goes next
    private fun seekDone(time: Long) {
        if (seekTarget != seekSent) sendSeek() else seekSettled(time)
    }

    private fun cancelSeek() {
        vlcHandler.removeCallbacksAndMessages(SEEK)
        seekTarget = TIME_UNSET
        seekSent = TIME_UNSET
    }

    private fun seekSettled(time: Long) {
        cancelSeek()
        position = time
        update { setVlcPlaybackState(STATE_READY) }
    }

    private fun selectTextTrack(trackId: String) {
        val slaveUri = trackId.substringAfter(SLAVE_PREFIX, "")
        if (slaveUri.isEmpty()) {
            dropPendingSlave()
            selectTrack(IMedia.Track.Type.Text, trackId)
            return
        }
        if (selectLoadedSlave(slaveUri)) return

        pendingSlave = slaveUri
        val hash = md5(slaveUri)
        if (!askedSlaves.add(hash)) {
            updateTracks()
            return
        }

        // a sub that never loads (dead url) must not leave playback paused, nor show as selected, forever
        postDelayed(SLAVE, 15_000) {
            if (loadedSlave(hash) == null) askedSlaves.remove(hash)
            if (pendingSlave == slaveUri) dropPendingSlave()
            updateTracks()
        }
        // vlc fetches subs on its input thread, a pause queued behind the fetch would only land once it is over
        if (player.isPlaying) {
            slavePaused = true
            player.pause()
            update { setVlcPlaybackState(STATE_BUFFERING) }
        }
        player.addSlave(IMedia.Slave.Type.Subtitle, slaveUri.toUri(), true)
        updateTracks()
    }

    private fun selectLoadedSlave(uri: String): Boolean {
        val es = loadedSlave(md5(uri)) ?: return false
        selectTrack(IMedia.Track.Type.Text, es.id)
        dropPendingSlave()
        return true
    }

    // vlc keys a slave's tracks by the md5 of its uri ("<md5(uri)>/spu/<n>")
    private fun loadedSlave(hash: String): IMedia.Track? =
        player.getTracks(IMedia.Track.Type.Text).orEmpty().firstOrNull { it.id.startsWith("$hash/spu/") }

    private fun dropPendingSlave() {
        pendingSlave = null
        if (!slavePaused) return
        slavePaused = false
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
    private val trackOrders = mutableMapOf<Pair<String, String>, Int>()

    private fun trackOrder(type: String, tracks: List<IMedia.Track>): Int {
        val key = type to trackKey(tracks.first())
        val es = tracks.minOf { it.id.substringAfterLast('/').toIntOrNull() ?: Int.MAX_VALUE }
        val order = minOf(trackOrders[key] ?: Int.MAX_VALUE, es)
        trackOrders[key] = order
        return order
    }

    private fun buildTracks(): Tracks {
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

        // sideloaded subs, listed from their config whether or not vlc fetched them yet
        val sideloaded = subtitleConfigs.map { config ->
            val track = loadedSlaves[config.uri]
            val format = Format.Builder()
                .setId("$SLAVE_PREFIX${config.uri}")
                .setLabel(config.label ?: track?.name)
                .setLanguage(config.language ?: track?.language)
                .setSampleMimeType("text/x-unknown")
                .build()
            val selected = config.uri == pendingSlaveUri || (track != null && track.id == selectedSubtitle?.id)
            Group(
                TrackGroup(config.id ?: "vlc-sub-${config.uri}", format),
                false,
                intArrayOf(FORMAT_HANDLED),
                booleanArrayOf(selected)
            )
        }

        return Tracks(
            trackGroups(
                "video",
                player.getTracks(IMedia.Track.Type.Video).orEmpty().groupBy { trackKey(it) }.values,
                player.getSelectedTrack(IMedia.Track.Type.Video)
            ) + trackGroups(
                "audio",
                player.getTracks(IMedia.Track.Type.Audio).orEmpty().groupBy { trackKey(it) }.values,
                player.getSelectedTrack(IMedia.Track.Type.Audio)
            ) + trackGroups(
                "text",
                textTracks.filter { it !in loadedSlaves.values }.groupBy { trackKey(it) }.values,
                selectedSubtitle
            ) + sideloaded
        )
    }

    // `type` is the mime's top-level type, which media3 reads the track type from
    private fun trackGroups(
        type: String,
        groups: Collection<List<IMedia.Track>>,
        selected: IMedia.Track?
    ): List<Group> = groups.sortedBy { trackOrder(type, it) }.map { tracks ->
        val formats = tracks.map { track ->
            val video = track as? VideoTrack
            Format.Builder()
                .setId(track.id)
                .setLabel(trackName(track))
                .setLanguage(track.language)
                .setSampleMimeType("$type/x-unknown")
                .setWidth(video?.width?.takeIf { it > 0 } ?: Format.NO_VALUE)
                .setHeight(video?.height?.takeIf { it > 0 } ?: Format.NO_VALUE)
                .setFrameRate(
                    video?.takeIf { it.frameRateDen > 0 && it.frameRateNum > 0 }
                        ?.let { it.frameRateNum.toFloat() / it.frameRateDen }
                        ?: Format.NO_VALUE.toFloat()
                )
                .setAverageBitrate(track.bitrate.takeIf { it > 0 } ?: Format.NO_VALUE)
                .build()
        }
        Group(
            // vlc's es ids change when a track is selected again, the key doesn't
            TrackGroup("vlc-$type-${trackKey(tracks.first())}", *formats.toTypedArray()),
            formats.size > 1,
            IntArray(formats.size) { FORMAT_HANDLED },
            BooleanArray(tracks.size) { tracks[it].id == selected?.id }
        )
    }

    override fun handleSetTrackSelectionParameters(
        trackSelectionParameters: TrackSelectionParameters
    ): ListenableFuture<*> = vlc {
        update { setTrackSelectionParameters(trackSelectionParameters) }
        selectTracks(trackSelectionParameters)
    }

    private fun selectTracks(params: TrackSelectionParameters) {
        val tracks = buildTracks()
        val groups = tracks.groups.map { it.mediaTrackGroup }
        val overrides = params.overrides.values
            // like exoplayer, an override only applies to the tracks it was made on, not to the next media's
            .filter { it.mediaTrackGroup in groups }
            .mapNotNull { override ->
                val index = override.trackIndices.firstOrNull() ?: return@mapNotNull null
                override.mediaTrackGroup.getFormat(index).id?.let { override.type to it }
            }.toMap()
        fun pick(type: Int, languages: List<String>, labels: List<String>): String? =
            overrides[type] ?: preferredTrack(tracks, type, languages, labels)

        if (TRACK_TYPE_VIDEO in params.disabledTrackTypes) {
            player.setVideoTrackEnabled(false)
        } else {
            player.setVideoTrackEnabled(true)
            pick(TRACK_TYPE_VIDEO, params.preferredVideoLanguages, params.preferredVideoLabels)
                ?.let { selectTrack(IMedia.Track.Type.Video, it) }
        }

        if (TRACK_TYPE_AUDIO in params.disabledTrackTypes) {
            player.unselectTrackType(IMedia.Track.Type.Audio)
        } else {
            pick(TRACK_TYPE_AUDIO, params.preferredAudioLanguages, params.preferredAudioLabels)
                ?.let { selectTrack(IMedia.Track.Type.Audio, it) }
        }

        if (TRACK_TYPE_TEXT in params.disabledTrackTypes) {
            dropPendingSlave()
            player.unselectTrackType(IMedia.Track.Type.Text)
        } else {
            pick(TRACK_TYPE_TEXT, params.preferredTextLanguages, params.preferredTextLabels)
                ?.let { selectTextTrack(it) }
        }
    }

    // vlc recreates the es of a track selected again (see trackOrders), restarting its decoder
    private fun selectTrack(type: Int, id: String) {
        if (player.getSelectedTrack(type)?.id != id) player.selectTrack(id)
    }

    // the track matching the most preferred language, then label
    private fun preferredTrack(
        tracks: Tracks,
        type: Int,
        preferredLanguages: List<String>,
        preferredLabels: List<String>,
    ): String? {
        val languages = preferredLanguages.filter { it.isNotEmpty() }
        val labels = preferredLabels.filter { it.isNotEmpty() }
        if (languages.isEmpty() && labels.isEmpty()) return null

        // null when it matches none of the preferences
        fun rank(preferences: List<String>, value: String?): Int? =
            if (preferences.isEmpty()) 0
            else preferences.indexOfFirst { it.equals(value, ignoreCase = true) }.takeIf { it >= 0 }

        return tracks.groups
            .filter { it.type == type }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) to group.isTrackSelected(it) } }
            .filter { (format) -> rank(languages, format.language) != null && rank(labels, format.label) != null }
            // among equally preferred tracks, the one playing (a rendition picked by hand) stays
            .minWithOrNull(compareBy({ rank(languages, it.first.language) }, { rank(labels, it.first.label) }, { !it.second }))
            ?.first?.id
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        if (videoOutput === boundOutput && vlcVout.areViewsAttached()) return Futures.immediateVoidFuture()
        // the video is laid out in a view, a bare Surface or SurfaceHolder has none
        if (videoOutput !is SurfaceView && videoOutput !is TextureView) return Futures.immediateVoidFuture()
        // libvlc only takes a new output once the previous one is detached
        if (vlcVout.areViewsAttached()) handleClearVideoOutput(null)

        boundOutput = videoOutput as View
        videoOutputGeneration++
        // clearing the previous output turned the video track off, only a rebuild turns it back on
        videoOutputStale = state.playbackState.let { it == STATE_READY || it == STATE_BUFFERING }
        if (videoOutput is SurfaceView) vlcVout.setVideoView(videoOutput) else vlcVout.setVideoView(videoOutput as TextureView)
        vlcVout.attachViews()
        videoOutput.addOnLayoutChangeListener(layoutListener)
        updateVideoLayout(videoOutput)
        rebuildVideoOutput()
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput != null && videoOutput !== boundOutput) return Futures.immediateVoidFuture()
        boundOutput?.removeOnLayoutChangeListener(layoutListener)
        boundOutput = null
        // the surface is gone once surfaceDestroyed returns, vlc has to be done with it by then.
        // libvlc can be stuck for seconds, so this only waits for so long.
        if (vlcVout.areViewsAttached()) {
            val done = CountDownLatch(1)
            val generation = videoOutputGeneration
            vlcHandler.post {
                if (generation != videoOutputGeneration ||
                    player.getSelectedTrack(IMedia.Track.Type.Video) == null
                ) {
                    done.countDown()
                    return@post
                }
                // onEvent counts it down once vlc closed the video
                videoReleased = done
                player.setVideoTrackEnabled(false)
            }
            done.await(250, TimeUnit.MILLISECONDS)
        }
        vlcVout.detachViews()
        return Futures.immediateVoidFuture()
    }

    // once the new surface settled (and again when vlc plays: a paused one draws nothing with it)
    private fun rebuildVideoOutput() {
        vlcHandler.removeCallbacksAndMessages(REBUILD)
        postDelayed(REBUILD, 200) {
            if (!videoOutputStale || !player.isPlaying || !vlcVout.areViewsAttached()) return@postDelayed
            videoOutputStale = false
            if (TRACK_TYPE_VIDEO in state.trackSelectionParameters.disabledTrackTypes) return@postDelayed
            player.setVideoTrackEnabled(false)
            player.setVideoTrackEnabled(true)
            player.updateVideoSurfaces()
        }
    }

    private val layoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateVideoLayout(view)
    }

    private fun updateVideoLayout(view: View) {
        if (view.width > 0 && view.height > 0) vlcVout.setWindowSize(view.width, view.height)
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

// an uncaught throw would kill the vlc thread, and the app with it
private class VlcHandler(looper: Looper) : Handler(looper) {
    override fun dispatchMessage(msg: Message) {
        try {
            super.dispatchMessage(msg)
        } catch (e: Exception) {
            Log.e("VlcPlayer", "vlc command failed", e)
        }
    }
}

// libvlc only lets subclasses pick the thread its events are handled on
private class EventMediaPlayer(libVLC: LibVLC) : MediaPlayer(libVLC) {
    fun setEventListener(listener: MediaPlayer.EventListener, handler: Handler) =
        super.setEventListener(listener, handler)
}
