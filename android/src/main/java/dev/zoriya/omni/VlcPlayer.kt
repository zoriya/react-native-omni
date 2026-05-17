package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BasePlayer
import androidx.media3.common.C.FORMAT_HANDLED
import androidx.media3.common.C.INDEX_UNSET
import androidx.media3.common.C.SELECTION_FLAG_DEFAULT
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.Tracks.Group
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.Size
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IMedia.VideoTrack
import org.videolan.libvlc.interfaces.IVLCVout

@SuppressLint("UnsafeOptInUsageError")
class VlcPlayer(ctx: Context) : BasePlayer(), MediaPlayer.EventListener {
    private val applicationLooper: Looper = Looper.getMainLooper()
    private val applicationHandler = Handler(applicationLooper)
    private val listeners = ListenerSet<Player.Listener>(
        applicationLooper,
        Clock.DEFAULT
    ) { listener, flags ->
        listener.onEvents(this, Player.Events(flags))
    }

    private fun notifyListeners(eventFlag: Int, callback: (Player.Listener) -> Unit) =
        notifyListeners(arrayOf(eventFlag), callback)

    private fun notifyListeners(eventFlags: Array<Int>, callback: (Player.Listener) -> Unit) {
        val flush = {
            eventFlags.forEach { listeners.queueEvent(it, callback) }
            listeners.flushEvents()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) flush() else applicationHandler.post(flush)
    }

    private val libVLC = LibVLC(ctx, arrayListOf(
        "--network-caching=1500",
        "--file-caching=1500",
        "--no-stats",
        "--no-osd",
        "--quiet"
    ))

    private val player = MediaPlayer(libVLC)

    private val vlcVout: IVLCVout = player.vlcVout

    private var mediaItems: List<MediaItem> = emptyList()
    private var currentMediaItemIndex: Int = INDEX_UNSET
    private var currentTrackSelectionParameters = TrackSelectionParameters.Builder().build()
    private var playerError: PlaybackException? = null
    private var playlistMetadata: MediaMetadata = MediaMetadata.EMPTY
    private var userInitiatedTransition: Boolean = false
    private var cachedBufferedPosition: Long = 0L

    private val availableCommands: Player.Commands = Player.Commands.Builder()
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
        .add(COMMAND_GET_CURRENT_MEDIA_ITEM)
        .add(COMMAND_GET_METADATA)
        .add(COMMAND_GET_TIMELINE)
        .add(COMMAND_GET_TRACKS)
        .add(COMMAND_SET_TRACK_SELECTION_PARAMETERS)
        .build()

    init {
        player.setEventListener(this)
    }

    override fun onEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Opening -> {
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_BUFFERING)
                }
            }

            MediaPlayer.Event.Playing -> {
                notifyListeners(
                    arrayOf(
                        EVENT_TIMELINE_CHANGED,
                        EVENT_MEDIA_METADATA_CHANGED,
                        EVENT_PLAYBACK_STATE_CHANGED,
                        EVENT_IS_PLAYING_CHANGED,
                        EVENT_TRACKS_CHANGED
                    )
                ) {
                    it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                    it.onMediaMetadataChanged(mediaMetadata)
                    it.onPlaybackStateChanged(STATE_READY)
                    it.onIsPlayingChanged(true)
                    it.onTracksChanged(getCurrentTracks())
                }
            }

            MediaPlayer.Event.Paused -> {
                notifyListeners(
                    arrayOf(EVENT_PLAY_WHEN_READY_CHANGED, EVENT_IS_PLAYING_CHANGED)
                ) {
                    it.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
                    it.onIsPlayingChanged(false)
                }
            }

            MediaPlayer.Event.Stopped -> {
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_IDLE)
                }
            }

            MediaPlayer.Event.EndReached -> {
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_ENDED)
                }
            }

            MediaPlayer.Event.Buffering -> {
                cachedBufferedPosition = (event.buffering * getDuration()).toLong()
                notifyListeners(arrayOf(EVENT_IS_LOADING_CHANGED, EVENT_PLAYBACK_STATE_CHANGED)) {
                    it.onIsLoadingChanged(true)
                    it.onPlaybackStateChanged(STATE_BUFFERING)
                }
            }

            MediaPlayer.Event.EncounteredError -> {
                val error = PlaybackException(
                    "VLC playback error",
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
                playerError = error
                notifyListeners(EVENT_PLAYER_ERROR) {
                    it.onPlayerErrorChanged(error)
                    it.onPlayerError(error)
                }
            }

            MediaPlayer.Event.TimeChanged -> {
                notifyListeners(EVENT_POSITION_DISCONTINUITY) {
                    val positionMs = getCurrentPosition()
                    val position = Player.PositionInfo(
                        currentMediaItemIndex,
                        currentMediaItemIndex,
                        getCurrentMediaItem(),
                        currentMediaItemIndex,
                        currentMediaItemIndex,
                        positionMs,
                        positionMs,
                        INDEX_UNSET,
                        INDEX_UNSET
                    )
                    it.onPositionDiscontinuity(position, position, DISCONTINUITY_REASON_SEEK)
                }
            }

            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                notifyListeners(EVENT_TRACKS_CHANGED) {
                    it.onTracksChanged(getCurrentTracks())
                }
            }

            MediaPlayer.Event.LengthChanged -> {
                notifyListeners(EVENT_TIMELINE_CHANGED) {
                    it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                }
            }
        }
    }

    override fun getApplicationLooper(): Looper = applicationLooper

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        setMediaItems(
            mediaItems,
            if (resetPosition) 0 else INDEX_UNSET,
            if (resetPosition) 0L else TIME_UNSET
        )
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) {
        val prev = currentMediaItemIndex
        val targetIndex = when {
            mediaItems.isEmpty() -> INDEX_UNSET
            startIndex in mediaItems.indices -> startIndex
            else -> 0
        }

        this.mediaItems = mediaItems
        currentMediaItemIndex = targetIndex
        playlistMetadata = mediaItems.getOrNull(targetIndex)?.mediaMetadata ?: MediaMetadata.EMPTY
        playerError = null

        player.stop()

        mediaItems.getOrNull(targetIndex)?.let { item ->
            val uri = item.localConfiguration?.uri?.toString()
            if (!uri.isNullOrEmpty()) {
                val media = Media(libVLC, uri.toUri())
                media.setHWDecoderEnabled(true, false)

                item.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
                    media.addSlave(
                        IMedia.Slave(IMedia.Slave.Type.Subtitle, 0, subtitle.uri.toString())
                    )
                }

                val targetMs = startPositionMs.coerceAtLeast(0L).takeIf { it != TIME_UNSET } ?: 0L
                media.addOption(":start-time=${targetMs / 1000.0}")

                player.setMedia(media)
            }
        }

        val events = mutableListOf(
            EVENT_TIMELINE_CHANGED,
            EVENT_MEDIA_METADATA_CHANGED,
            EVENT_PLAYLIST_METADATA_CHANGED
        )
        if (prev != currentMediaItemIndex) events += EVENT_MEDIA_ITEM_TRANSITION
        val reason = if (userInitiatedTransition) MEDIA_ITEM_TRANSITION_REASON_SEEK else MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        userInitiatedTransition = false
        notifyListeners(events.toTypedArray()) {
            it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            it.onMediaMetadataChanged(mediaMetadata)
            it.onPlaylistMetadataChanged(playlistMetadata)
            if (prev != currentMediaItemIndex) {
                it.onMediaItemTransition(currentMediaItem, reason)
            }
        }
    }

    override fun addMediaItems(
        p0: Int,
        p1: List<MediaItem>
    ) = Unit

    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = Unit

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) {
        setMediaItems(mediaItems)
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {
        val prev = currentMediaItemIndex
        mediaItems = emptyList()
        currentMediaItemIndex = INDEX_UNSET
        playlistMetadata = MediaMetadata.EMPTY
        player.stop()

        val events = mutableListOf(
            EVENT_TIMELINE_CHANGED,
            EVENT_MEDIA_METADATA_CHANGED,
            EVENT_PLAYLIST_METADATA_CHANGED
        )
        if (prev != INDEX_UNSET) events += EVENT_MEDIA_ITEM_TRANSITION
        notifyListeners(events.toTypedArray()) {
            it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            it.onMediaMetadataChanged(mediaMetadata)
            it.onPlaylistMetadataChanged(playlistMetadata)
            if (prev != INDEX_UNSET) {
                it.onMediaItemTransition(null, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
            }
        }
    }

    override fun getAvailableCommands(): Player.Commands = availableCommands

    override fun prepare() {
        player.play()
    }

    override fun getPlaybackState(): Int =
        when {
            currentMediaItemIndex == INDEX_UNSET -> STATE_IDLE
            player.media == null -> STATE_IDLE
            player.isPlaying -> STATE_READY
            player.isSeekable && player.time >= player.length && player.length > 0 -> STATE_ENDED
            else -> STATE_READY
        }

    override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = playerError

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) player.play() else player.pause()
    }

    override fun getPlayWhenReady(): Boolean = player.isPlaying

    override fun setRepeatMode(repeatMode: Int) = Unit

    override fun getRepeatMode(): Int = REPEAT_MODE_OFF

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading() = player.playerState == IMedia.State.Opening

    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
        isRepeatingCurrentItem: Boolean
    ) {
        val targetIndex = if (mediaItemIndex == INDEX_UNSET) currentMediaItemIndex else mediaItemIndex
        if (targetIndex == INDEX_UNSET || targetIndex !in mediaItems.indices) return

        if (targetIndex != currentMediaItemIndex) {
            userInitiatedTransition = true
            setMediaItems(mediaItems, targetIndex, positionMs)
            return
        }

        player.time = positionMs.coerceAtLeast(0L).takeIf { it != TIME_UNSET } ?: 0L
    }

    override fun getSeekBackIncrement(): Long = 5_000L

    override fun getSeekForwardIncrement(): Long = 15_000L

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        player.rate = playbackParameters.speed.coerceAtLeast(0f)
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        PlaybackParameters(player.rate.coerceAtLeast(0f))

    override fun stop() {
        player.stop()
    }

    override fun release() {
        player.setEventListener(null)
        player.stop()
        clearVideoSurface()
        player.release()
        libVLC.release()
    }

    override fun getCurrentTracks(): Tracks {
        val result = ArrayList<Group>()
        val selectedVideo = player.getSelectedTrack(IMedia.Track.Type.Video)
        val selectedAudio = player.getSelectedTrack(IMedia.Track.Type.Audio)
        val selectedSubtitle = player.getSelectedTrack(IMedia.Track.Type.Text)

        val videoTracks = player.getTracks(IMedia.Track.Type.Video)
        if (!videoTracks.isNullOrEmpty()) {
            val videoFormats = videoTracks.map { track ->
                Format.Builder()
                    .setId(track.id)
                    .setLabel(track.name)
                    .setSampleMimeType("video/x-unknown")
                    .build()
            }
            val group = TrackGroup("vlc-video", *videoFormats.toTypedArray())
            val selected = BooleanArray(videoFormats.size) { idx ->
                selectedVideo != null && selectedVideo.id == videoTracks[idx].id
            }
            val support = IntArray(videoFormats.size) { FORMAT_HANDLED }
            result.add(Group(group, videoFormats.size > 1, support, selected))
        }

        player.getTracks(IMedia.Track.Type.Audio)?.forEach { track ->
            val format = Format.Builder()
                .setId(track.id)
                .setLabel(track.name)
                .setSampleMimeType("audio/x-unknown")
                .build()
            val group = TrackGroup("vlc-audio-${track.id}", format)
            val selected = booleanArrayOf(selectedAudio != null && selectedAudio.id == track.id)
            val support = intArrayOf(FORMAT_HANDLED)
            result.add(Group(group, false, support, selected))
        }

        player.getTracks(IMedia.Track.Type.Text)?.forEach { track ->
            val format = Format.Builder()
                .setId(track.id)
                .setLabel(track.name)
                .setSampleMimeType("text/x-unknown")
                .build()
            val group = TrackGroup("vlc-sub-${track.id}", format)
            val selected = booleanArrayOf(selectedSubtitle != null && selectedSubtitle.id == track.id)
            val support = intArrayOf(FORMAT_HANDLED)
            result.add(Group(group, false, support, selected))
        }

        return if (result.isEmpty()) Tracks.EMPTY else Tracks(result)
    }

    override fun getTrackSelectionParameters(): TrackSelectionParameters =
        currentTrackSelectionParameters

    override fun setTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters) {
        currentTrackSelectionParameters = trackSelectionParameters
        val videoDisabled = trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_VIDEO)
        val audioDisabled = trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_AUDIO)
        val textDisabled = trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_TEXT)

        if (videoDisabled) player.setVideoTrackEnabled(false)
        if (audioDisabled) player.unselectTrackType(IMedia.Track.Type.Audio)
        if (textDisabled) player.unselectTrackType(IMedia.Track.Type.Text)

        val tracks = getCurrentTracks()

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
                    player.selectTrack(trackId)
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
            textPreference?.let { player.selectTrack(it) }
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

        data class Candidate(val id: String, val langIndex: Int, val labelIndex: Int, val hasDefaultFlag: Boolean)

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

    override fun getMediaMetadata(): MediaMetadata =
        getCurrentMediaItem()?.mediaMetadata ?: MediaMetadata.EMPTY

    override fun getPlaylistMetadata(): MediaMetadata = playlistMetadata

    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) {
        playlistMetadata = mediaMetadata
    }

    override fun getCurrentTimeline(): Timeline {
        if (mediaItems.isEmpty()) return Timeline.EMPTY
        return object : Timeline() {
            private fun durationUsForIndex(index: Int): Long {
                if (index != currentMediaItemIndex) return TIME_UNSET
                val dur = duration
                return if (dur == TIME_UNSET) TIME_UNSET else dur * 1000L
            }

            override fun getWindowCount(): Int = mediaItems.size

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long
            ): Window {
                check(windowIndex in mediaItems.indices)
                return window.set(
                    windowIndex,
                    mediaItems[windowIndex],
                    null,
                    TIME_UNSET,
                    TIME_UNSET,
                    TIME_UNSET,
                    true,
                    false,
                    null,
                    0L,
                    durationUsForIndex(windowIndex),
                    0,
                    mediaItems.size - 1,
                    0L
                )
            }

            override fun getPeriodCount(): Int = mediaItems.size

            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                check(periodIndex in mediaItems.indices)
                return period.set(periodIndex, periodIndex, 0, durationUsForIndex(periodIndex), 0L)
            }

            override fun getIndexOfPeriod(uid: Any): Int {
                return if (uid is Int && uid in mediaItems.indices) uid else INDEX_UNSET
            }

            override fun getUidOfPeriod(periodIndex: Int): Any {
                check(periodIndex in mediaItems.indices)
                return periodIndex
            }
        }
    }

    override fun getCurrentPeriodIndex() = currentMediaItemIndex

    override fun getCurrentMediaItemIndex() = currentMediaItemIndex

    override fun getDuration(): Long = player.length.takeIf { it > 0 } ?: TIME_UNSET

    override fun getCurrentPosition() = player.time.coerceAtLeast(0L)

    override fun getBufferedPosition(): Long {
        val duration = getDuration()
        if (duration == TIME_UNSET) return getCurrentPosition()
        return cachedBufferedPosition.coerceAtMost(duration)
    }

    override fun getTotalBufferedDuration() = bufferedPosition

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = INDEX_UNSET

    override fun getCurrentAdIndexInAdGroup(): Int = INDEX_UNSET

    override fun getMaxSeekToPreviousPosition() = 3_000L

    override fun getContentPosition() = getCurrentPosition()

    override fun getContentBufferedPosition() = getBufferedPosition()

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setVolume(volume: Float) {
        player.volume = (volume * 100).toInt()
    }

    override fun getVolume(): Float = player.volume / 100f

    override fun mute() {
        player.volume = 0
    }

    override fun unmute() {
        player.volume = 100
    }

    override fun clearVideoSurface() {
        vlcVout.detachViews()
    }

    override fun clearVideoSurface(surface: Surface?) {
        clearVideoSurface()
    }

    override fun setVideoSurface(surface: Surface?) {
        if (surface == null) {
            clearVideoSurface()
            return
        }
        vlcVout.setVideoSurface(surface, null)
        vlcVout.attachViews()
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        if (surfaceHolder == null) return clearVideoSurface()
        val surface = surfaceHolder.surface
        if (surface != null && surface.isValid && !vlcVout.areViewsAttached()) {
            vlcVout.setVideoSurface(surface, surfaceHolder)
            vlcVout.attachViews()
        }
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        clearVideoSurface()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        vlcVout.setVideoView(surfaceView ?: return clearVideoSurface())
        vlcVout.attachViews()
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        clearVideoSurface()
    }

    override fun setVideoTextureView(textureView: TextureView?) = Unit

    override fun clearVideoTextureView(textureView: TextureView?) = Unit

    override fun getVideoSize(): VideoSize {
        val videoTrack = player.getSelectedTrack(IMedia.Track.Type.Video) as? VideoTrack ?: return VideoSize.UNKNOWN
        if (videoTrack.width <= 0 || videoTrack.height <= 0) return VideoSize.UNKNOWN
        return VideoSize(videoTrack.width, videoTrack.height)
    }

    override fun getSurfaceSize(): Size = Size.UNKNOWN

    override fun getCurrentCues(): CueGroup = CueGroup.EMPTY_TIME_ZERO

    override fun getDeviceInfo(): DeviceInfo = DeviceInfo.UNKNOWN

    override fun getDeviceVolume(): Int = 0

    override fun isDeviceMuted(): Boolean = false

    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) = Unit

    override fun setDeviceVolume(volume: Int, flags: Int) = Unit

    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() = Unit

    override fun increaseDeviceVolume(flags: Int) = Unit

    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() = Unit

    override fun decreaseDeviceVolume(flags: Int) = Unit

    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) = Unit

    override fun setDeviceMuted(muted: Boolean, flags: Int) = Unit

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) =
        Unit
}
