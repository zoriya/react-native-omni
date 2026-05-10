package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.BasePlayer
import androidx.media3.common.C.FORMAT_HANDLED
import androidx.media3.common.C.INDEX_UNSET
import androidx.media3.common.C.TIME_UNSET
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_UNKNOWN
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
import dev.jdtech.mpv.MPVLib

@SuppressLint("UnsafeOptInUsageError")
class MpvPlayer(ctx: Context) : BasePlayer(), MPVLib.EventObserver {
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

    private fun notifyListeners(eventFlag: Array<Int>, callback: (Player.Listener) -> Unit) {
        val notifyAction = {
            for (event in eventFlag) {
                listeners.queueEvent(event, callback)
            }
            listeners.flushEvents()
        }
        if (Looper.myLooper() == applicationLooper) {
            notifyAction()
        } else {
            applicationHandler.post(notifyAction)
        }
    }

    internal val mpv = (MPVLib.create(ctx) ?: throw Error("Failed to initialize MPVLib")).apply {
        setOptionString("vo", "gpu-next")
        setOptionString("force-window", "no")
        setOptionString("gpu-context", "android")
        setOptionString("opengl-es", "yes")
        setOptionString("hwdec", "mediacodec-copy")
        setOptionString("profile", "fast")
        setOptionString("keep-open", "always")

        setOptionString("cache", "yes")
        setOptionString("cache-pause-initial", "yes")
        setOptionString("demuxer-max-bytes", "150MiB")
        setOptionString("demuxer-max-back-bytes", "75MiB")
        setOptionString("demuxer-readahead-secs", "20")

        setOptionString("save-position-on-quit", "no")
        setOptionString("ytdl", "no")
        setOptionString("hr-seek", "no")

        init()
        addObserver(this@MpvPlayer)
        observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("vid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
    }

    private var mediaItems: List<MediaItem> = emptyList()
    private var currentMediaItemIndex: Int = INDEX_UNSET
    private var currentTrackSelectionParameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    private var playerError: PlaybackException? = null
    private var playlistMetadata: MediaMetadata = MediaMetadata.EMPTY
    private var userInitiatedTransition: Boolean = false

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
        val target = if (targetIndex != INDEX_UNSET) mediaItems[targetIndex] else null
        playlistMetadata = target?.mediaMetadata ?: MediaMetadata.EMPTY
        playerError = null

        mpv.command(arrayOf("stop"))

        if (target != null) {
            val targetMs = if (startPositionMs == TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0L)
            mpv.setPropertyDouble("start", targetMs / 1000.0)

            val uri = target.localConfiguration?.uri?.toString()
            if (!uri.isNullOrEmpty()) {
                mpv.command(arrayOf("loadfile", uri, "replace"))
                target.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
                    mpv.command(arrayOf("sub-add", subtitle.uri.toString(), "cached"))
                }
            }
        }

        val events = arrayListOf(
            EVENT_TIMELINE_CHANGED,
            EVENT_MEDIA_METADATA_CHANGED,
            EVENT_PLAYLIST_METADATA_CHANGED
        )
        if (prev != currentMediaItemIndex) {
            events.add(EVENT_MEDIA_ITEM_TRANSITION)
        }
        val transitionReason = if (userInitiatedTransition) {
            MEDIA_ITEM_TRANSITION_REASON_SEEK
        } else {
            MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        }
        userInitiatedTransition = false
        notifyListeners(events.toTypedArray()) {
            it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            it.onMediaMetadataChanged(mediaMetadata)
            it.onPlaylistMetadataChanged(playlistMetadata)
            if (prev != currentMediaItemIndex) {
                it.onMediaItemTransition(
                    currentMediaItem,
                    transitionReason
                )
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
        mpv.command(arrayOf("stop"))

        var events = arrayListOf(
            EVENT_TIMELINE_CHANGED,
            EVENT_MEDIA_METADATA_CHANGED,
            EVENT_PLAYLIST_METADATA_CHANGED
        )
        if (prev != INDEX_UNSET) {
            events.add(EVENT_MEDIA_ITEM_TRANSITION)
        }
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

    override fun prepare() = Unit

    override fun getPlaybackState(): Int =
        when (true) {
            (currentMediaItemIndex == INDEX_UNSET) -> STATE_IDLE
            mpv.getPropertyBoolean("paused-for-cache") -> STATE_BUFFERING
            mpv.getPropertyBoolean("eof-reached") -> STATE_ENDED
            else -> STATE_READY
        }

    override fun getPlaybackSuppressionReason(): Int = PLAYBACK_SUPPRESSION_REASON_NONE

    override fun getPlayerError(): PlaybackException? = playerError

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        mpv.setPropertyBoolean("pause", !playWhenReady)
    }

    override fun getPlayWhenReady(): Boolean = !(mpv.getPropertyBoolean("pause") ?: true)

    override fun setRepeatMode(repeatMode: Int) = Unit

    override fun getRepeatMode(): Int = REPEAT_MODE_OFF

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading(): Boolean = mpv.getPropertyBoolean("paused-for-cache") ?: false

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

        val targetMs = if (positionMs == TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        mpv.command(arrayOf("seek", (targetMs / 1000.0).toString(), "absolute"))
    }

    override fun getSeekBackIncrement(): Long = 5_000L

    override fun getSeekForwardIncrement(): Long = 15_000L

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        mpv.setPropertyDouble("speed", playbackParameters.speed.coerceAtLeast(0f).toDouble())
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return PlaybackParameters(
            (mpv.getPropertyDouble("speed") ?: 1.0).toFloat().coerceAtLeast(0f)
        )
    }

    override fun stop() {
        mpv.command(arrayOf("stop"))
    }

    override fun release() {
        mpv.removeObserver(this)
        clearVideoSurface()
        mpv.destroy()
    }

    override fun getCurrentTracks(): Tracks {
        val count = mpv.getPropertyInt("track-list/count") ?: 0
        if (count <= 0) return Tracks.EMPTY

        data class Entry(val id: Int, val format: Format)

        val grouped = LinkedHashMap<Int, MutableList<Entry>>()
        for (i in 0 until count) {
            val base = "track-list/$i"
            val type = when (mpv.getPropertyString("$base/type")) {
                "video" -> TRACK_TYPE_VIDEO
                "audio" -> TRACK_TYPE_AUDIO
                "sub" -> TRACK_TYPE_TEXT
                else -> TRACK_TYPE_UNKNOWN
            }
            if (type == TRACK_TYPE_UNKNOWN) continue

            val id = mpv.getPropertyInt("$base/id") ?: continue
            val label = mpv.getPropertyString("$base/title")
            val language = mpv.getPropertyString("$base/lang")
            val codec = mpv.getPropertyString("$base/codec")

            val format = Format.Builder()
                .setId(id.toString())
                .setLabel(label)
                .setLanguage(language)
                .setCodecs(codec)
                .build()

            grouped.getOrPut<Int, MutableList<Entry>>(type) { mutableListOf<Entry>() }
                .add(Entry(id, format))
        }
        val selectedVideo = mpv.getPropertyInt("vid")
        val selectedAudio = mpv.getPropertyInt("aid")
        val selectedSubtitle = mpv.getPropertyInt("sid")
        val result = ArrayList<Group>()
        for ((type, entries) in grouped) {
            if (entries.isEmpty()) continue
            val group = TrackGroup("mpv-$type", *entries.map { it.format }.toTypedArray<Format>())
            val selected = BooleanArray(entries.size) { idx ->
                val id = entries[idx].id
                when (type) {
                    TRACK_TYPE_VIDEO -> selectedVideo == id
                    TRACK_TYPE_AUDIO -> selectedAudio == id
                    TRACK_TYPE_TEXT -> selectedSubtitle == id
                    else -> false
                }
            }
            val support = IntArray(entries.size) { FORMAT_HANDLED }
            result.add(Group(group, false, support, selected))
        }
        return Tracks(result)
    }

    override fun getTrackSelectionParameters(): TrackSelectionParameters =
        currentTrackSelectionParameters

    override fun setTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters) {
        currentTrackSelectionParameters = trackSelectionParameters
        if (trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_VIDEO)) {
            mpv.setPropertyString("vid", "no")
        }
        if (trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_AUDIO)) {
            mpv.setPropertyString("aid", "no")
        }
        if (trackSelectionParameters.disabledTrackTypes.contains(TRACK_TYPE_TEXT)) {
            mpv.setPropertyString("sid", "no")
        }
        for (override in trackSelectionParameters.overrides.values) {
            val selectedIndex = override.trackIndices.firstOrNull() ?: continue
            if (selectedIndex !in 0 until override.mediaTrackGroup.length) continue
            val trackId =
                override.mediaTrackGroup.getFormat(selectedIndex).id?.toIntOrNull() ?: continue

            when (override.type) {
                TRACK_TYPE_VIDEO -> mpv.setPropertyInt("vid", trackId)
                TRACK_TYPE_AUDIO -> mpv.setPropertyInt("aid", trackId)
                TRACK_TYPE_TEXT -> mpv.setPropertyInt("sid", trackId)
            }
        }
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
            override fun getWindowCount(): Int = mediaItems.size

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long
            ): Window {
                check(windowIndex in mediaItems.indices)
                val item = mediaItems[windowIndex]
                val durationUs = if (windowIndex == currentMediaItemIndex) {
                    val dur = duration
                    if (dur == TIME_UNSET) TIME_UNSET else dur * 1000L
                } else TIME_UNSET
                return window.set(
                    windowIndex,
                    item,
                    null,
                    TIME_UNSET,
                    TIME_UNSET,
                    TIME_UNSET,
                    true,
                    false,
                    null,
                    0L,
                    durationUs,
                    0,
                    mediaItems.size-1,
                    0L
                )
            }

            override fun getPeriodCount(): Int = mediaItems.size

            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                check(periodIndex in mediaItems.indices)
                val durationUs = if (periodIndex == currentMediaItemIndex) {
                    val dur = duration
                    if (dur == TIME_UNSET) TIME_UNSET else dur * 1000L
                } else TIME_UNSET
                return period.set(periodIndex, periodIndex, 0, durationUs, 0L)
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

    override fun getCurrentPeriodIndex() = if (currentMediaItemIndex == INDEX_UNSET) INDEX_UNSET else currentMediaItemIndex

    override fun getCurrentMediaItemIndex() = currentMediaItemIndex

    override fun getDuration(): Long {
        val duration = mpv.getPropertyDouble("duration") ?: return TIME_UNSET
        if (!duration.isFinite() || duration <= 0.0) return TIME_UNSET
        return (duration * 1000.0).toLong()
    }

    override fun getCurrentPosition() =
        ((mpv.getPropertyDouble("time-pos") ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()

    override fun getBufferedPosition() =
        ((mpv.getPropertyDouble("demuxer-cache-time") ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
            .coerceAtLeast(0L)

    override fun getTotalBufferedDuration() = bufferedPosition

    override fun isPlayingAd(): Boolean = false

    override fun getCurrentAdGroupIndex(): Int = INDEX_UNSET

    override fun getCurrentAdIndexInAdGroup(): Int = INDEX_UNSET

    override fun getMaxSeekToPreviousPosition() = 3_000L

    override fun getContentPosition() = getCurrentPosition()

    override fun getContentBufferedPosition() = getBufferedPosition()

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setVolume(volume: Float) {
        mpv.setPropertyDouble("volume", volume.coerceIn(0f, 1f) * 100.0)
    }

    override fun getVolume(): Float {
        return ((mpv.getPropertyDouble("volume") ?: 100.0).coerceIn(0.0, 100.0) / 100.0).toFloat()
    }

    override fun mute() {
        mpv.setPropertyBoolean("mute", true)
    }

    override fun unmute() {
        mpv.setPropertyBoolean("mute", false)
    }

    override fun clearVideoSurface() {
        mpv.setOptionString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    override fun clearVideoSurface(surface: Surface?) {
        clearVideoSurface()
    }

    override fun setVideoSurface(surface: Surface?) {
        mpv.attachSurface(surface ?: return clearVideoSurface())
        mpv.setOptionString("force-window", "yes")
        mpv.setOptionString("vo", "gpu-next")
    }

    fun updateSurfaceSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            mpv.setPropertyString("android-surface-size", "${width}x${height}")
        }
    }

    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        val holder = surfaceHolder ?: return clearVideoSurface()
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            setVideoSurface(surface)
            val frame = holder.surfaceFrame
            val width = frame?.width() ?: 0
            val height = frame?.height() ?: 0
            updateSurfaceSize(width, height)
        }
    }

    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        clearVideoSurface()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView?) {
        val view = surfaceView ?: return clearVideoSurface()
        setVideoSurfaceHolder(view.holder)
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        val view = surfaceView ?: return clearVideoSurface()
        clearVideoSurfaceHolder(view.holder)
    }

    override fun setVideoTextureView(textureView: TextureView?) = Unit

    override fun clearVideoTextureView(textureView: TextureView?) = Unit

    override fun getVideoSize(): VideoSize = VideoSize.UNKNOWN

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

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE ->
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_BUFFERING)
                }

            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED ->
                notifyListeners(
                    arrayOf(
                        EVENT_TIMELINE_CHANGED,
                        EVENT_MEDIA_METADATA_CHANGED,
                        EVENT_PLAYBACK_STATE_CHANGED,
                        EVENT_IS_PLAYING_CHANGED
                    )
                ) {
                    it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                    it.onMediaMetadataChanged(mediaMetadata)
                    it.onPlaybackStateChanged(STATE_READY)
                    it.onIsPlayingChanged(playWhenReady)
                }

            MPVLib.MpvEvent.MPV_EVENT_SEEK,
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                val positionMs = getCurrentPosition()
                val position = Player.PositionInfo(
                    currentMediaItemIndex,
                    getCurrentMediaItemIndex(),
                    getCurrentMediaItem(),
                    currentMediaItemIndex,
                    getCurrentPeriodIndex(),
                    positionMs,
                    positionMs,
                    INDEX_UNSET,
                    INDEX_UNSET
                )
                notifyListeners(EVENT_POSITION_DISCONTINUITY) {
                    it.onPositionDiscontinuity(position, position, DISCONTINUITY_REASON_SEEK)
                }
            }

            MPVLib.MpvEvent.MPV_EVENT_END_FILE ->
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_ENDED)
                }

            MPVLib.MpvEvent.MPV_EVENT_QUEUE_OVERFLOW -> {
                playerError = PlaybackException(
                    "mpv event queue overflow",
                    null,
                    PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
                )
                notifyListeners(EVENT_PLAYER_ERROR) {
                    it.onPlayerErrorChanged(playerError)
                    it.onPlayerError(
                        playerError ?: PlaybackException(
                            "unknown",
                            null,
                            PlaybackException.ERROR_CODE_UNSPECIFIED
                        )
                    )
                }
            }
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "vid", "aid", "sid" -> {
                notifyListeners(EVENT_TRACKS_CHANGED) { it.onTracksChanged(getCurrentTracks()) }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "duration" ->
                notifyListeners(arrayOf(EVENT_TIMELINE_CHANGED, EVENT_MEDIA_METADATA_CHANGED)) {
                    it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                    it.onMediaMetadataChanged(mediaMetadata)
                }

            "speed" ->
                notifyListeners(EVENT_PLAYBACK_PARAMETERS_CHANGED) {
                    it.onPlaybackParametersChanged(
                        PlaybackParameters(
                            value.toFloat().coerceAtLeast(0f)
                        )
                    )
                }

            "volume" ->
                notifyListeners(EVENT_VOLUME_CHANGED) {
                    it.onVolumeChanged((value.coerceIn(0.0, 100.0) / 100.0).toFloat())
                }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" ->
                notifyListeners(arrayOf(EVENT_PLAY_WHEN_READY_CHANGED, EVENT_IS_PLAYING_CHANGED)) {
                    it.onPlayWhenReadyChanged(
                        !value,
                        PLAY_WHEN_READY_CHANGE_REASON_REMOTE
                    )
                    it.onIsPlayingChanged(!value)
                }

            "core-idle", "eof-reached" ->
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(getPlaybackState())
                }

            "paused-for-cache" -> {
                notifyListeners(arrayOf(EVENT_IS_LOADING_CHANGED, EVENT_PLAYBACK_STATE_CHANGED)) {
                    it.onIsLoadingChanged(value)
                    it.onPlaybackStateChanged(getPlaybackState())
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) = Unit


}
