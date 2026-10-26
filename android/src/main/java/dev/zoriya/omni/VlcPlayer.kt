package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
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

// a sideloaded sub, listed from its config whether or not vlc fetched it yet
private const val SLAVE_PREFIX = "vlc-sub:"

// work delayed on the vlc thread: a sub being fetched, the video track restarting for a new output
private val SLAVE = Any()
private val REBUILD = Any()

@SuppressLint("UnsafeOptInUsageError")
class VlcPlayer(ctx: Context) :
    SimpleBasePlayer(Looper.getMainLooper()),
    MediaPlayer.EventListener {
    private val applicationHandler = Handler(Looper.getMainLooper())

    // libvlc calls can block for seconds (on tvs), they all run on this thread instead of the ui's
    private val vlcThread = HandlerThread("vlc-commands").apply { start() }
    private val vlcHandler = VlcHandler(vlcThread.looper)
    // libvlc clears its event handler's queue when the listener changes, keep commands out of it
    private val vlcEventHandler = VlcHandler(vlcThread.looper)

    private val libVLC = LibVLC(
        ctx, arrayListOf(
            "--file-caching=1500",
            // the app picks where a media starts and its tracks, vlc would resume its own history
            "--restore-playback-pos=0",
            "--no-restore-playback-states",
            "--no-stats",
            "--no-osd",
            "--quiet"
        )
    )
    private val player = EventMediaPlayer(libVLC)
    private val vlcVout: IVLCVout = player.vlcVout

    private val audioManager =
        ContextCompat.getSystemService(ctx.applicationContext, AudioManager::class.java)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var handleAudioFocus = false
    // a single one: the audio manager tells requests apart by their listener
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { vlcHandler.post { onAudioFocusChange(it) } }

    private var boundOutput: SurfaceView? = null
    // bumped for every output set, a clear reaching vlc late must leave a newer one alone
    @Volatile
    private var videoOutputGeneration = 0
    private var videoReleased: CountDownLatch? = null
    // the video track a cleared output turned off
    private var releasedVideoTrack: String? = null
    private var voutCount = 0

    @Volatile
    private var position = 0L
    // vlc keeps reporting its pre-seek time until it decoded the new position
    @Volatile
    private var seekTarget = TIME_UNSET
    // vlc restarts its demuxer for each seek, a burst of them (double tapping to skip a minute)
    // waits for the one under way
    private var seekSent = TIME_UNSET
    private var seekOrigin = 0L
    // vlc drops a seek sent while it handles another, one it never lands is sent again
    private var seekSentAt = 0L

    // vlc keeps sending events about a media we left until it opens the next one
    private var stale = false
    // vlc played the media we opened, its tracks are known and it can seek
    private var started = false
    private var pendingSlave: String? = null
    private var slavePaused = false
    // hashes of the sideloaded subs vlc was asked to fetch
    private val askedSlaves = mutableSetOf<String>()

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

    private fun update(block: State.Builder.() -> Unit) {
        state = state.buildUpon().apply(block).build()
        applicationHandler.post { invalidateState() }
    }

    private fun State.Builder.updateCurrentItem(block: MediaItemData.Builder.() -> Unit): State.Builder =
        setPlaylist(state.playlist.map { it.buildUpon().apply(block).build() })

    private fun updateTracks() {
        update { updateCurrentItem { setTracks(buildTracks()) } }
    }

    // vlc doesn't leave idle on its own (prepare/play do), and a seek or a sub fetch keeps it buffering
    private fun State.Builder.setVlcPlaybackState(playbackState: Int): State.Builder {
        if (state.playbackState == STATE_IDLE) return this
        val waiting = (seekTarget != TIME_UNSET && state.playWhenReady) || slavePaused
        return setPlaybackState(if (playbackState == STATE_READY && waiting) STATE_BUFFERING else playbackState)
    }

    override fun onEvent(event: Event) {
        // vlc skips Opening when it never went idle between two medias
        if (event.type == Event.Opening || event.type == Event.MediaChanged) stale = false
        if (event.type == Event.Vout) voutCount = event.voutCount
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
                if (!started) {
                    started = true
                    // vlc ignores seeks until now, and a live stream never seeks
                    if (!player.isSeekable) seekTarget = TIME_UNSET
                    if (seekTarget != TIME_UNSET) sendSeek()
                    selectTracks(state.trackSelectionParameters)
                }
                if (releasedVideoTrack != null) rebuildVideoOutput()
                update {
                    updateCurrentItem {
                        setDurationUs(if (player.length > 0) player.length * 1000L else TIME_UNSET)
                            .setIsSeekable(player.isSeekable)
                            .setTracks(buildTracks())
                    }
                    setVlcPlaybackState(STATE_READY)
                }
                updateVideoSize()
                if (state.playWhenReady && !requestAudioFocus()) pauseForFocusLoss()
            }

            Event.Paused -> when {
                slavePaused -> Unit
                // vlc opened the media paused (see loadMedia)
                state.playWhenReady && state.playbackSuppressionReason == PLAYBACK_SUPPRESSION_REASON_NONE -> player.play()
                else -> update { setVlcPlaybackState(STATE_READY) }
            }

            Event.EndReached -> {
                leaveMedia()
                update { setVlcPlaybackState(STATE_ENDED) }
                abandonAudioFocus()
            }

            Event.EncounteredError -> {
                leaveMedia()
                val error = PlaybackException(
                    "VLC playback error",
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
                update { setPlayerError(error).setPlaybackState(STATE_IDLE) }
                abandonAudioFocus()
            }

            Event.TimeChanged -> {
                position = event.timeChanged
                if (seekSent == TIME_UNSET) return
                // vlc lands near the target (on a segment's start for hls), past the halfway point.
                // its clock freezes while it seeks, one still ticking from the origin was dropped
                if (abs(position - seekSent) > abs(position - seekOrigin)) {
                    if (SystemClock.uptimeMillis() - seekSentAt > 2_000) sendSeek()
                    return
                }
                if (seekTarget != seekSent) return sendSeek()
                seekTarget = TIME_UNSET
                seekSent = TIME_UNSET
                update { setVlcPlaybackState(STATE_READY) }
            }

            // vlc has no video size event, this is the first one where it is known
            Event.PositionChanged -> if (state.videoSize == VideoSize.UNKNOWN) updateVideoSize()

            Event.ESAdded, Event.ESDeleted, Event.ESSelected -> {
                // vlc ignores a slave's `select` flag while another sub plays
                pendingSlave?.let { selectLoadedSlave(it) }
                updateTracks()
                updateVideoSize()
            }

            Event.LengthChanged -> update {
                updateCurrentItem {
                    setDurationUs(if (event.lengthChanged > 0) event.lengthChanged * 1000L else TIME_UNSET)
                }
            }

            Event.SeekableChanged -> update { updateCurrentItem { setIsSeekable(event.seekable) } }
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
        // prev/next are NavigationPlayer's
        val item = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
        update {
            setPlaylist(listOfNotNull(item).map { MediaItemData.Builder(Any()).setMediaItem(it).setIsSeekable(true).build() })
            setCurrentMediaItemIndex(if (item == null) INDEX_UNSET else 0)
            // an idle player waits for prepare/play
            if (state.playbackState != STATE_IDLE) setPlaybackState(if (item == null) STATE_ENDED else STATE_BUFFERING)
            setPlayerError(null)
            setVideoSize(VideoSize.UNKNOWN)
        }
        if (item == null) abandonAudioFocus()
        loadMedia(item)
        // TIME_UNSET is negative
        position = startPositionMs.coerceAtLeast(0L)
        if (state.playbackState != STATE_IDLE) openMedia()
    }

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
        // this takes the focus back from a transient loss as well
        if (playWhenReady && !state.timeline.isEmpty && !requestAudioFocus()) return@vlc pauseForFocusLoss()
        update {
            setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            if (playWhenReady) setPlaybackSuppressionReason(PLAYBACK_SUPPRESSION_REASON_NONE)
        }
        when {
            !playWhenReady -> player.pause()
            state.playbackState == STATE_IDLE -> openMedia()
            state.playbackState == STATE_ENDED -> {
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
        player.stop()
        update { setPlaybackState(STATE_IDLE) }
        abandonAudioFocus()
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
            // like exoplayer, going back after the end opens the media there
            STATE_ENDED -> {
                position = target
                openMedia()
            }
            else -> {
                if (!state.playlist[0].isSeekable) return@vlc
                seekTarget = target
                if (started && seekSent == TIME_UNSET) sendSeek()
                update { setVlcPlaybackState(STATE_READY) }
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

    private fun sendSeek() {
        seekSent = seekTarget
        seekOrigin = player.time
        seekSentAt = SystemClock.uptimeMillis()
        player.time = seekTarget
    }

    // vlc opens the media again (replay, prepare after a stop) with its default tracks and no slave
    private fun leaveMedia() {
        seekTarget = TIME_UNSET
        seekSent = TIME_UNSET
        vlcHandler.removeCallbacksAndMessages(SLAVE)
        slavePaused = false
        pendingSlave = null
        askedSlaves.clear()
        trackOrders.clear()
        stale = true
        started = false
    }

    private fun playVlc() {
        if (!slavePaused) player.play()
    }

    private fun loadMedia(item: MediaItem?) {
        leaveMedia()
        releasedVideoTrack = null
        voutCount = 0
        player.stop()
        val uri = item?.localConfiguration?.uri?.toString()
        if (!uri.isNullOrEmpty()) {
            val media = Media(libVLC, uri.toUri())
            media.setHWDecoderEnabled(true, false)
            // vlc doesn't allow arbitrary headers :c
            val extras = item.requestMetadata.extras
            for (name in extras?.keySet().orEmpty()) {
                val value = extras?.getString(name) ?: continue
                when (name.lowercase()) {
                    "user-agent" -> media.addOption(":http-user-agent=$value")
                    "referer", "referrer" -> media.addOption(":http-referrer=$value")
                }
            }
            // vlc only opens a media by playing it, this stops it on the first frame (see the Paused event)
            media.addOption(":start-paused")
            player.setMedia(media)
            media.release()
        }
        updateTracks()
    }

    private fun openMedia() {
        if (state.timeline.isEmpty) return
        // vlc's `:start-time` trims the media instead of starting further in, the Playing event seeks there
        if (position > 0L) seekTarget = position
        update { setPlayerError(null).setPlaybackState(STATE_BUFFERING) }
        playVlc()
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
        vlcHandler.postAtTime({
            if (loadedSlave(hash) == null) askedSlaves.remove(hash)
            if (pendingSlave == slaveUri) dropPendingSlave()
            updateTracks()
        }, SLAVE, SystemClock.uptimeMillis() + 15_000)
        // vlc fetches subs on its input thread, a pause sent meanwhile would only land once it is over
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

    // vlc names a slave's tracks "<md5(uri)>/spu/<n>"
    private fun loadedSlave(hash: String): IMedia.Track? =
        player.getTracks(IMedia.Track.Type.Text).orEmpty().firstOrNull { it.id.startsWith("$hash/spu/") }

    private fun dropPendingSlave() {
        pendingSlave = null
        if (!slavePaused) return
        slavePaused = false
        if (state.playWhenReady) playVlc() else update { setVlcPlaybackState(STATE_READY) }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // hls describes an adaptive track as "<GROUP-ID> <NAME>", listing it once per quality group
    private fun trackName(track: IMedia.Track): String? {
        val description = track.description
        if (description == null || !track.id.contains("/auto/")) return track.name
        return description.substringAfter(' ')
    }

    // the renditions of an adaptive track share its name (like media3's hls source), vlc describes the
    // one it plays from its segments, without the group-id and with the container's language
    private fun trackKey(track: IMedia.Track): String =
        if (track.id.contains("/auto/")) trackName(track) ?: track.id
        else track.id

    // vlc renumbers a track each time it's selected, they are ordered on the first number they had
    private val trackOrders = mutableMapOf<Pair<String, String>, Int>()

    private fun buildTracks(): Tracks {
        val subtitleConfigs = state.playlist.firstOrNull()?.mediaItem?.localConfiguration?.subtitleConfigurations.orEmpty()
        val textTracks = player.getTracks(IMedia.Track.Type.Text).orEmpty()
        val subtitleSlavesByHash = subtitleConfigs.associateBy { md5(it.uri.toString()) }
        val loadedSlaves = textTracks.mapNotNull { track ->
            subtitleSlavesByHash[track.id.substringBefore("/spu/", "")]?.let { it.uri to track }
        }.toMap()
        // vlc still plays the previous sub while it fetches the selected one
        val pendingSlaveUri = pendingSlave?.toUri()?.takeIf { it !in loadedSlaves }
        val selectedSubtitle =
            if (pendingSlaveUri != null) null else player.getSelectedTrack(IMedia.Track.Type.Text)

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

    // media3 reads the track type from the mime's top-level type
    private fun trackGroups(
        type: String,
        groups: Collection<List<IMedia.Track>>,
        selected: IMedia.Track?
    ): List<Group> = groups.sortedBy { tracks ->
        val es = tracks.minOf { it.id.substringAfterLast('/').toIntOrNull() ?: Int.MAX_VALUE }
        trackOrders.merge(type to trackKey(tracks.first()), es) { a, b -> minOf(a, b) }
    }.map { tracks ->
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
            // like exoplayer, an override only applies to the tracks it was made on
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

    // vlc restarts the decoder of a track selected again
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
            // the one playing (a rendition picked by hand) wins a tie
            .minWithOrNull(compareBy({ rank(languages, it.first.language) }, { rank(labels, it.first.label) }, { !it.second }))
            ?.first?.id
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        if (videoOutput === boundOutput) return Futures.immediateVoidFuture()
        // the video is laid out in a view, a bare Surface or SurfaceHolder has none
        if (videoOutput !is SurfaceView) return Futures.immediateVoidFuture()
        handleClearVideoOutput(null)
        boundOutput = videoOutput
        videoOutputGeneration++
        vlcVout.setVideoView(videoOutput)
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
        if (!vlcVout.areViewsAttached()) return Futures.immediateVoidFuture()
        // the surface is gone once surfaceDestroyed returns, vlc has to close its video before.
        // libvlc can be stuck for seconds, so this only waits for so long.
        val done = CountDownLatch(1)
        val generation = videoOutputGeneration
        vlcHandler.post {
            if (generation != videoOutputGeneration || player.getSelectedTrack(IMedia.Track.Type.Video) == null) {
                done.countDown()
                return@post
            }
            // onEvent counts it down once vlc closed the video
            videoReleased = done
            releasedVideoTrack = player.getSelectedTrack(IMedia.Track.Type.Video)?.id
            player.unselectTrackType(IMedia.Track.Type.Video)
        }
        done.await(250, TimeUnit.MILLISECONDS)
        vlcVout.detachViews()
        return Futures.immediateVoidFuture()
    }

    // vlc doesn't draw on a new output by itself. once the surface settled, and again when vlc plays:
    // a paused one draws nothing on it
    private fun rebuildVideoOutput() {
        vlcHandler.removeCallbacksAndMessages(REBUILD)
        vlcHandler.postAtTime({
            if (!player.isPlaying || !vlcVout.areViewsAttached()) return@postAtTime
            // nothing to do when vlc already draws on it (an output set while the media opens)
            if (releasedVideoTrack == null && voutCount > 0) return@postAtTime
            if (TRACK_TYPE_VIDEO in state.trackSelectionParameters.disabledTrackTypes) return@postAtTime
            // `setVideoTrackEnabled(true)` picks the first track, vlc then reports none as selected
            val track = player.getSelectedTrack(IMedia.Track.Type.Video)?.id ?: releasedVideoTrack ?: return@postAtTime
            releasedVideoTrack = null
            player.unselectTrackType(IMedia.Track.Type.Video)
            player.selectTrack(track)
            player.updateVideoSurfaces()
        }, REBUILD, SystemClock.uptimeMillis() + 200)
    }

    private val layoutListener = View.OnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) updateVideoLayout(view)
    }

    private fun updateVideoLayout(view: View) {
        if (view.width > 0 && view.height > 0) vlcVout.setWindowSize(view.width, view.height)
        vlcHandler.post { player.updateVideoSurfaces() }
    }

    // OmniView sizes the content frame (and the pip aspect ratio) to the video
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
