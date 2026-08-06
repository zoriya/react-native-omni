package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.core.content.ContextCompat
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
import java.security.MessageDigest

@SuppressLint("UnsafeOptInUsageError")
class VlcPlayer(ctx: Context) :
    BasePlayer(),
    MediaPlayer.EventListener,
    AudioManager.OnAudioFocusChangeListener {
    private val applicationLooper: Looper = Looper.getMainLooper()
    private val applicationHandler = Handler(applicationLooper)

    private val audioManager =
        ContextCompat.getSystemService(ctx.applicationContext, AudioManager::class.java)
    private var audioFocusRequest: AudioFocusRequest? = null

    private var hasAudioFocus = false
    private var handleAudioFocus = false
    private var mediaAudioAttributes: AudioAttributes = AudioAttributes.DEFAULT
    private var playbackSuppression = PLAYBACK_SUPPRESSION_REASON_NONE

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
    @Volatile
    private var playerError: PlaybackException? = null
    @Volatile
    private var released = false
    private var playlistMetadata: MediaMetadata = MediaMetadata.EMPTY
    private var userInitiatedTransition: Boolean = false
    @Volatile
    private var cachedBufferedPosition: Long = 0L
    private var boundSurfaceView: SurfaceView? = null
    private var lastVideoSize: VideoSize = VideoSize.UNKNOWN

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
        .add(COMMAND_CHANGE_MEDIA_ITEMS)
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
                lastVideoSize = VideoSize.UNKNOWN
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_BUFFERING)
                }
            }

            MediaPlayer.Event.Playing -> {
                if (!requestAudioFocus()) applicationHandler.post { player.pause() }
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
                maybeNotifyVideoSizeChanged()
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
                abandonAudioFocus()
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_IDLE)
                }
            }

            MediaPlayer.Event.EndReached -> {
                abandonAudioFocus()
                notifyListeners(EVENT_PLAYBACK_STATE_CHANGED) {
                    it.onPlaybackStateChanged(STATE_ENDED)
                }
            }

            MediaPlayer.Event.Buffering -> {
                val dur = getDuration()
                if (dur != TIME_UNSET) {
                    cachedBufferedPosition = (event.buffering / 100.0 * dur).toLong()
                }
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

            // progress is polled instead of pushed dozens of times per seconds.
            // this improves perf & battery life (native -> js bridge is expensive)
            MediaPlayer.Event.TimeChanged -> Unit

            // vlc has no video-size event, Vout event still has unknown size.
            // This is the first callback with known size.
            MediaPlayer.Event.PositionChanged -> {
                if (lastVideoSize == VideoSize.UNKNOWN) maybeNotifyVideoSizeChanged()
            }

            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                notifyListeners(EVENT_TRACKS_CHANGED) {
                    it.onTracksChanged(getCurrentTracks())
                }
                maybeNotifyVideoSizeChanged()
            }

            MediaPlayer.Event.LengthChanged -> {
                notifyListeners(EVENT_TIMELINE_CHANGED) {
                    it.onTimelineChanged(currentTimeline, TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
                }
            }
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (playbackSuppression != PLAYBACK_SUPPRESSION_REASON_NONE) {
                    setPlaybackSuppression(PLAYBACK_SUPPRESSION_REASON_NONE)
                    player.play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                setPlaybackSuppression(PLAYBACK_SUPPRESSION_REASON_NONE)
                player.pause()
                notifyListeners(arrayOf(EVENT_PLAY_WHEN_READY_CHANGED, EVENT_IS_PLAYING_CHANGED)) {
                    it.onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                    it.onIsPlayingChanged(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                setPlaybackSuppression(PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
                player.pause()
            }
        }
    }

    private fun setPlaybackSuppression(reason: Int) {
        if (playbackSuppression == reason) return
        playbackSuppression = reason
        notifyListeners(EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED) {
            it.onPlaybackSuppressionReasonChanged(reason)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val audioManager = audioManager ?: return true
        if (!handleAudioFocus) {
            abandonAudioFocus()
            return true
        }
        if (hasAudioFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAudioAttributes.platformAudioAttributes)
                .setOnAudioFocusChangeListener(this, applicationHandler)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        val audioManager = audioManager ?: return
        if (!hasAudioFocus && audioFocusRequest == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
        hasAudioFocus = false
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
                applyRequestHeaders(media, item.requestMetadata.extras)

                item.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
                    media.addSlave(
                        IMedia.Slave(IMedia.Slave.Type.Subtitle, 0, subtitle.uri.toString())
                    )
                }

                val targetMs = startPositionMs.coerceAtLeast(0L).takeIf { it != TIME_UNSET } ?: 0L
                media.addOption(":start-time=${targetMs / 1000.0}")

                player.setMedia(media)
                media.release()
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

    // vlc keys each slave's tracks by the md5 of its uri ("<md5(uri)>/spu/<n>")
    private fun subtitleSlaveForTrackId(trackId: String): MediaItem.SubtitleConfiguration? {
        val hash = trackId.substringBefore("/spu/", "")
        if (hash.isEmpty()) return null
        return mediaItems.getOrNull(currentMediaItemIndex)
            ?.localConfiguration?.subtitleConfigurations
            ?.firstOrNull { md5(it.uri.toString()) == hash }
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

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
            playerError != null -> STATE_IDLE
            currentMediaItemIndex == INDEX_UNSET -> STATE_IDLE
            player.media?.also { it.release() } == null -> STATE_IDLE
            player.playerState == IMedia.State.Opening -> STATE_BUFFERING
            player.isPlaying -> STATE_READY
            player.isSeekable && player.time >= player.length && player.length > 0 -> STATE_ENDED
            else -> STATE_READY
        }

    override fun getPlaybackSuppressionReason(): Int = playbackSuppression

    override fun getPlayerError(): PlaybackException? = playerError

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) player.play() else player.pause()
    }

    override fun getPlayWhenReady(): Boolean = player.isPlaying

    override fun setRepeatMode(repeatMode: Int) = Unit

    override fun getRepeatMode(): Int = REPEAT_MODE_OFF

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit

    override fun getShuffleModeEnabled(): Boolean = false

    override fun isLoading(): Boolean {
        val state = playbackState
        if (state == STATE_IDLE || state == STATE_ENDED) return false
        return player.playerState == IMedia.State.Opening
    }

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
            player.play()
            return
        }

        val from = getCurrentPosition()
        val target = positionMs.coerceAtLeast(0L).takeIf { it != TIME_UNSET } ?: 0L
        player.time = target
        // since vlc reports progress events every few ms they don't have a seek finished event.
        notifyListeners(EVENT_POSITION_DISCONTINUITY) {
            it.onPositionDiscontinuity(
                positionInfo(from),
                positionInfo(target),
                DISCONTINUITY_REASON_SEEK
            )
        }
    }

    private fun positionInfo(positionMs: Long): Player.PositionInfo =
        Player.PositionInfo(
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

    override fun getSeekBackIncrement(): Long = 5_000L

    override fun getSeekForwardIncrement(): Long = 15_000L

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        player.rate = playbackParameters.speed.coerceAtLeast(0.01f)
    }

    override fun getPlaybackParameters(): PlaybackParameters =
        PlaybackParameters(player.rate.takeIf { it > 0f } ?: 1f)

    override fun stop() {
        player.stop()
    }

    override fun release() {
        if (released) return
        released = true
        player.setEventListener(null)
        listeners.release()
        abandonAudioFocus()
        player.stop()
        clearVideoSurface()
        applicationHandler.post {
            player.release()
            libVLC.release()
        }
    }

    override fun getCurrentTracks(): Tracks {
        val result = ArrayList<Group>()
        val selectedVideo = player.getSelectedTrack(IMedia.Track.Type.Video)
        val selectedAudio = player.getSelectedTrack(IMedia.Track.Type.Audio)
        val selectedSubtitle = player.getSelectedTrack(IMedia.Track.Type.Text)

        player.getTracks(IMedia.Track.Type.Video).orEmpty()
            .groupBy { listOf(it.language, it.name, it.description) }
            .values.forEach { videoTracks ->
                val videoFormats = videoTracks.map { track ->
                    val videoTrack = track as? VideoTrack
                    Format.Builder()
                        .setId(track.id)
                        .setLabel(track.name)
                        .setLanguage(track.language)
                        .setSampleMimeType("video/x-unknown")
                        .setWidth(videoTrack?.width?.takeIf { it > 0 } ?: Format.NO_VALUE)
                        .setHeight(videoTrack?.height?.takeIf { it > 0 } ?: Format.NO_VALUE)
                        .setFrameRate(
                            videoTrack?.takeIf { it.frameRateDen > 0 }
                                ?.let { it.frameRateNum.toFloat() / it.frameRateDen }
                                ?: Format.NO_VALUE.toFloat()
                        )
                        .setAverageBitrate(track.bitrate.takeIf { it > 0 } ?: Format.NO_VALUE)
                        .build()
                }
                val group = TrackGroup("vlc-video-${videoTracks.minOf { it.id }}", *videoFormats.toTypedArray())
                val selected = BooleanArray(videoFormats.size) { idx ->
                    selectedVideo != null && selectedVideo.id == videoTracks[idx].id
                }
                val support = IntArray(videoFormats.size) { FORMAT_HANDLED }
                result.add(Group(group, videoFormats.size > 1, support, selected))
            }

        player.getTracks(IMedia.Track.Type.Audio).orEmpty()
            .groupBy { listOf(it.language, it.name, it.description) }
            .values.forEach { audioTracks ->
                val audioFormats = audioTracks.map { track ->
                    Format.Builder()
                        .setId(track.id)
                        .setLabel(track.name)
                        .setLanguage(track.language)
                        .setSampleMimeType("audio/x-unknown")
                        .build()
                }
                val group = TrackGroup("vlc-audio-${audioTracks.minOf { it.id }}", *audioFormats.toTypedArray())
                val selected = BooleanArray(audioFormats.size) { idx ->
                    selectedAudio != null && selectedAudio.id == audioTracks[idx].id
                }
                val support = IntArray(audioFormats.size) { FORMAT_HANDLED }
                result.add(Group(group, audioFormats.size > 1, support, selected))
            }

        player.getTracks(IMedia.Track.Type.Text)?.forEach { track ->
            // external subs don't have label/language, match them back to surface them
            val slave = subtitleSlaveForTrackId(track.id)
            val format = Format.Builder()
                .setId(track.id)
                .setLabel(slave?.label ?: track.name)
                .setLanguage(slave?.language ?: track.language)
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
        // snapshot the current playlist for the returned timeline.
        // timelines are immutable
        val items = mediaItems
        val currentIndex = currentMediaItemIndex
        val currentDurationUs = duration.takeIf { it != TIME_UNSET }?.let { it * 1000L } ?: TIME_UNSET
        if (items.isEmpty()) return Timeline.EMPTY
        return object : Timeline() {
            private fun durationUsForIndex(index: Int): Long =
                if (index == currentIndex) currentDurationUs else TIME_UNSET

            override fun getWindowCount(): Int = items.size

            override fun getWindow(
                windowIndex: Int,
                window: Window,
                defaultPositionProjectionUs: Long
            ): Window {
                val index = windowIndex.coerceIn(items.indices)
                return window.set(
                    index,
                    items[index],
                    null,
                    TIME_UNSET,
                    TIME_UNSET,
                    TIME_UNSET,
                    true,
                    false,
                    null,
                    0L,
                    durationUsForIndex(index),
                    index,
                    index,
                    0L
                )
            }

            override fun getPeriodCount(): Int = items.size

            override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                val index = periodIndex.coerceIn(items.indices)
                return period.set(index, index, index, durationUsForIndex(index), 0L)
            }

            override fun getIndexOfPeriod(uid: Any): Int {
                return if (uid is Int && uid in items.indices) uid else INDEX_UNSET
            }

            override fun getUidOfPeriod(periodIndex: Int): Any {
                return periodIndex.coerceIn(items.indices)
            }
        }
    }

    override fun getCurrentPeriodIndex() = currentMediaItemIndex.coerceAtLeast(0)

    override fun getCurrentMediaItemIndex() = currentMediaItemIndex.coerceAtLeast(0)

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

    override fun getAudioAttributes(): AudioAttributes = mediaAudioAttributes

    private var volumeBeforeMute: Float = 1f

    override fun setVolume(volume: Float) {
        player.volume = (volume.coerceIn(0f, 1f) * 100).toInt()
        notifyVolumeChanged()
    }

    override fun getVolume(): Float = (player.volume / 100f).coerceIn(0f, 1f)

    override fun mute() {
        val current = getVolume()
        if (current > 0f) volumeBeforeMute = current
        player.volume = 0
        notifyVolumeChanged()
    }

    override fun unmute() {
        setVolume(volumeBeforeMute.takeIf { it > 0f } ?: 1f)
    }

    private fun notifyVolumeChanged() {
        notifyListeners(EVENT_VOLUME_CHANGED) { it.onVolumeChanged(getVolume()) }
    }

    override fun clearVideoSurface() {
        boundSurfaceView = null
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
        if (surfaceView == null) return clearVideoSurface()
        if (vlcVout.areViewsAttached() && boundSurfaceView === surfaceView) return
        boundSurfaceView = surfaceView
        // Hand VLC the SurfaceView (not a raw Surface) so it reads the real view
        // size and keeps the video layout correct as the surface resizes (e.g. the
        // PIP window shrinking/growing); see updateVideoLayout for size updates.
        vlcVout.setVideoView(surfaceView)
        vlcVout.attachViews()
    }

    /**
     * VLC tears down its video decoder + output whenever the output Surface is
     * destroyed (e.g. the SurfaceView being reparented for PIP) and does not
     * rebuild them when a new Surface arrives — the picture stays black. Toggling
     * the video track forces VLC to spin up a fresh decoder/vout against the
     * currently-attached Surface. Safe no-op when nothing is attached/playing.
     */
    fun rebuildVideoOutput() {
        if (!vlcVout.areViewsAttached() || !player.isPlaying) return
        player.setVideoTrackEnabled(false)
        player.setVideoTrackEnabled(true)
        player.updateVideoSurfaces()
    }

    /** Recompute the video layout for the current surface size. Must run whenever
     * the surface resizes (VLC latches a stale geometry otherwise -> the picture
     * renders at the wrong size, anchored in a corner). */
    fun updateVideoLayout(width: Int, height: Int) {
        if (!vlcVout.areViewsAttached()) return
        if (width > 0 && height > 0) vlcVout.setWindowSize(width, height)
        player.updateVideoSurfaces()
    }

    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) {
        if (surfaceView != null && boundSurfaceView !== surfaceView) return
        clearVideoSurface()
    }

    override fun setVideoTextureView(textureView: TextureView?) = Unit

    override fun clearVideoTextureView(textureView: TextureView?) = Unit

    /**
     * VLC never pushes a video-size event, so emit our own once the selected video
     * track (and its sample aspect ratio) is known. Listeners such as OmniView use
     * this to size the content frame -> the PIP aspect ratio matches the video
     * instead of the raw SurfaceView bounds (ExoPlayer does this on its own).
     */
    private fun maybeNotifyVideoSizeChanged() {
        val size = getVideoSize()
        if (size == VideoSize.UNKNOWN || size == lastVideoSize) return
        lastVideoSize = size
        notifyListeners(EVENT_VIDEO_SIZE_CHANGED) {
            it.onVideoSizeChanged(size)
        }
    }

    override fun getVideoSize(): VideoSize {
        val videoTrack = player.getSelectedTrack(IMedia.Track.Type.Video) as? VideoTrack ?: return VideoSize.UNKNOWN
        if (videoTrack.width <= 0 || videoTrack.height <= 0) return VideoSize.UNKNOWN
        val par = if (videoTrack.sarNum > 0 && videoTrack.sarDen > 0) {
            videoTrack.sarNum.toFloat() / videoTrack.sarDen.toFloat()
        } else {
            1f
        }
        return VideoSize(videoTrack.width, videoTrack.height, par)
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

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) {
        mediaAudioAttributes = audioAttributes
        this.handleAudioFocus = handleAudioFocus
        if (!handleAudioFocus) {
            abandonAudioFocus()
        } else if (player.isPlaying && !requestAudioFocus()) {
            applicationHandler.post { player.pause() }
        }
        notifyListeners(EVENT_AUDIO_ATTRIBUTES_CHANGED) {
            it.onAudioAttributesChanged(audioAttributes)
        }
    }
}
