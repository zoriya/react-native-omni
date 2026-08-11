package dev.zoriya.omni

import android.annotation.SuppressLint
import androidx.media3.common.C
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.margelo.nitro.omni.BoolProperty
import com.margelo.nitro.omni.CastStatus
import com.margelo.nitro.omni.HybridOmniEventMapSpec
import com.margelo.nitro.omni.NumberProperty
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Source
import com.margelo.nitro.omni.Track
import com.margelo.nitro.omni.TrackProperty

/**
 * Source of the current track/rendition lists. Implemented by [OmniPlayer] so
 * state emissions reuse the same computation (incl. cast metadata recovery) as
 * its readonly getters.
 */
interface TrackProvider {
    val videos: Array<Track>
    val audios: Array<Track>
    val subtitles: Array<Track>
    val renditions: Array<Rendition>
}

@SuppressLint("UnsafeOptInUsageError")
class EventMap(private val tracks: TrackProvider) : HybridOmniEventMapSpec(), Player.Listener {
    private val onPrevListeners = mutableSetOf<() -> Unit>()
    private val onNextListeners = mutableSetOf<() -> Unit>()
    private val onEndListeners = mutableSetOf<() -> Unit>()
    private val onErrorListeners = mutableSetOf<(type: String, message: String) -> Unit>()
    private val onAudioFocusChangeListeners = mutableSetOf<(status: String) -> Unit>()
    private val onVideoTrackChangeListeners = mutableSetOf<(track: Track) -> Unit>()
    private val onAudioTrackChangeListeners = mutableSetOf<(track: Track) -> Unit>()
    private val onSubtitleChangeListeners = mutableSetOf<(track: Track?) -> Unit>()
    private val onRenditionChangeListeners = mutableSetOf<(rendition: Rendition) -> Unit>()
    private val tracksListeners = mutableMapOf<TrackProperty, MutableSet<(Array<Track>) -> Unit>>()
    private val renditionsListeners = mutableSetOf<(Array<Rendition>) -> Unit>()
    private val stateListeners = mutableMapOf<NumberProperty, MutableSet<(Double) -> Unit>>()
    private val stateBoolListeners = mutableMapOf<BoolProperty, MutableSet<(Boolean) -> Unit>>()
    private val playerStatusListeners = mutableSetOf<(PlayerStatus) -> Unit>()
    private val castStatusListeners = mutableSetOf<(CastStatus) -> Unit>()
    private val sourceListeners = mutableSetOf<(Source?) -> Unit>()

    private var lastRendition: Rendition? = null
    private var lastRenditions: Array<Rendition>? = null
    private var lastIsAutoQuality: Boolean? = null

    // swapped on cast start/end
    private var _player: Player? = null
    var player: Player
        get() = _player!!
        set(value) {
            if (_player === value) return
            _player?.removeListener(this)
            _player = value
            value.addListener(this)
            lastRendition = null
            lastRenditions = null
            lastIsAutoQuality = null

            // immediatly send all the state
            val status = when (value.playbackState) {
                STATE_BUFFERING -> PlayerStatus.LOADING
                STATE_READY -> PlayerStatus.READYTOPLAY
                else -> PlayerStatus.IDLE
            }
            playerStatusListeners.forEach { it(status) }
            stateBoolListeners[BoolProperty.ISPLAYING]?.forEach { it(value.isPlaying) }
            stateBoolListeners[BoolProperty.MUTED]?.forEach { it(value.volume <= 0f) }
            stateListeners[NumberProperty.VOLUME]?.forEach { it(value.volume.toDouble()) }
            stateListeners[NumberProperty.PLAYBACKRATE]?.forEach {
                it(value.playbackParameters.speed.toDouble())
            }
            stateListeners[NumberProperty.CURRENTTIME]?.forEach {
                it((value.currentPosition.toDouble() / 1000.0).coerceAtLeast(0.0))
            }
            stateListeners[NumberProperty.BUFFERED]?.forEach {
                it((value.totalBufferedDuration.toDouble() / 1000.0).coerceAtLeast(0.0))
            }
            stateListeners[NumberProperty.DURATION]?.forEach {
                val duration = value.duration
                it(
                    if (duration == C.TIME_UNSET) 0.0 else (duration.toDouble() / 1000.0).coerceAtLeast(
                        0.0
                    )
                )
            }
            onTracksChanged(value.currentTracks)
        }

    fun emitCastStatus(status: CastStatus) {
        castStatusListeners.forEach { it(status) }
    }

    fun emitSource(source: Source?) {
        sourceListeners.forEach { it(source) }
    }

    fun emitPrev() {
        onPrevListeners.forEach { it() }
    }

    fun emitNext() {
        onNextListeners.forEach { it() }
    }

    private fun selectedTrack(trackType: Int): Track? {
        val list = when (trackType) {
            TRACK_TYPE_VIDEO -> tracks.videos
            TRACK_TYPE_AUDIO -> tracks.audios
            TRACK_TYPE_TEXT -> tracks.subtitles
            else -> return null
        }
        return list.firstOrNull { it.selected }
    }

    private fun getCurrentRendition(): Rendition? {
        val group = player.currentTracks.groups.firstOrNull {
            it.isSelected && it.type == TRACK_TYPE_VIDEO
        } ?: return null

        val isAuto = player.trackSelectionParameters.overrides.none {
            it.key.type == TRACK_TYPE_VIDEO
        }

        val currentIndex = when {
            isAuto -> {
                if (player.videoSize.width > 0 && player.videoSize.height > 0) {
                    (0 until group.length).firstOrNull { i ->
                        val format = group.getTrackFormat(i)
                        format.width == player.videoSize.width && format.height == player.videoSize.height
                    }
                } else null
            }

            else -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }
        } ?: return null

        val format = group.getTrackFormat(currentIndex)
        return Rendition(
            id = currentIndex.toString(),
            width = format.width.toDouble().coerceAtLeast(0.0),
            height = format.height.toDouble().coerceAtLeast(0.0),
            bitrate = format.bitrate.toDouble().coerceAtLeast(0.0),
            selected = true
        )
    }

    private fun emitIsAutoQualityChange() {
        val isAuto = player.trackSelectionParameters.overrides.none {
            it.key.type == C.TRACK_TYPE_VIDEO
        }
        if (isAuto == lastIsAutoQuality) return
        lastIsAutoQuality = isAuto
        stateBoolListeners[BoolProperty.ISAUTOQUALITY]?.forEach { it(isAuto) }
    }

    private fun emitTracks() {
        tracksListeners[TrackProperty.VIDEOS]?.let { cbs ->
            val videos = tracks.videos
            cbs.forEach { it(videos) }
        }
        tracksListeners[TrackProperty.AUDIOS]?.let { cbs ->
            val audios = tracks.audios
            cbs.forEach { it(audios) }
        }
        tracksListeners[TrackProperty.SUBTITLES]?.let { cbs ->
            val subtitles = tracks.subtitles
            cbs.forEach { it(subtitles) }
        }
    }

    private fun emitRenditionChange() {
        val rendition = getCurrentRendition() ?: return
        if (rendition == lastRendition) return
        lastRendition = rendition
        onRenditionChangeListeners.forEach { it(rendition) }
    }

    private fun emitRenditions() {
        if (renditionsListeners.isEmpty()) return
        val renditions = tracks.renditions
        renditionsListeners.forEach { it(renditions) }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val state = when (player.playbackState) {
            STATE_IDLE -> PlayerStatus.IDLE
            STATE_BUFFERING -> PlayerStatus.LOADING
            STATE_READY -> PlayerStatus.READYTOPLAY
            STATE_ENDED -> {
                onEndListeners.forEach { it() }
                PlayerStatus.IDLE
            }

            else -> PlayerStatus.IDLE
        }
        playerStatusListeners.forEach { it(state) }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
            onAudioFocusChangeListeners.forEach { it("loss") }
        }
        onIsPlayingChanged(player.isPlaying)
    }

    override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        val status =
            if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
                "lossTransient"
            else "gain"
        onAudioFocusChangeListeners.forEach { it(status) }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        stateBoolListeners[BoolProperty.ISPLAYING]?.forEach { it(isPlaying) }
    }

    override fun onVolumeChanged(volume: Float) {
        stateListeners[NumberProperty.VOLUME]?.forEach { it(volume.toDouble()) }
        stateBoolListeners[BoolProperty.MUTED]?.forEach { it(volume <= 0.0) }
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        stateListeners[NumberProperty.PLAYBACKRATE]?.forEach {
            it(playbackParameters.speed.toDouble())
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        stateListeners[NumberProperty.DURATION]?.forEach {
            val duration = player.duration
            it(
                if (duration == C.TIME_UNSET) 0.0
                else (duration.toDouble() / 1000.0).coerceAtLeast(0.0)
            )
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        selectedTrack(TRACK_TYPE_VIDEO)?.let { track ->
            onVideoTrackChangeListeners.forEach { it(track) }
        }
        selectedTrack(TRACK_TYPE_AUDIO)?.let { track ->
            onAudioTrackChangeListeners.forEach { it(track) }
        }
        val subtitle = selectedTrack(TRACK_TYPE_TEXT)
        onSubtitleChangeListeners.forEach { it(subtitle) }
        emitTracks()
        emitIsAutoQualityChange()
        emitRenditionChange()
        emitRenditions()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        emitIsAutoQualityChange()
        emitRenditionChange()
        emitRenditions()
    }

    override fun onPlayerError(error: PlaybackException) {
        onErrorListeners.forEach {
            it(error.errorCodeName, error.message ?: "unknown message")
        }
        playerStatusListeners.forEach { it(PlayerStatus.ERROR) }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        stateListeners[NumberProperty.CURRENTTIME]?.forEach {
            it((player.currentPosition.toDouble() / 1000.0).coerceAtLeast(0.0))
        }
        stateListeners[NumberProperty.BUFFERED]?.forEach {
            it((player.totalBufferedDuration.toDouble() / 1000.0).coerceAtLeast(0.0))
        }
        stateListeners[NumberProperty.DURATION]?.forEach {
            val duration = player.duration
            it(
                if (duration == C.TIME_UNSET) 0.0 else (duration.toDouble() / 1000.0).coerceAtLeast(
                    0.0
                )
            )
        }
    }

    override fun addStateListener(key: NumberProperty, cb: (value: Double) -> Unit) {
        stateListeners.getOrPut(key) { mutableSetOf() }.add(cb)
    }

    override fun removeStateListener(key: NumberProperty, cb: (value: Double) -> Unit) {
        stateListeners[key]?.remove(cb)
    }

    override fun addStateBoolListener(key: BoolProperty, cb: (value: Boolean) -> Unit) {
        stateBoolListeners.getOrPut(key) { mutableSetOf() }.add(cb)
    }

    override fun removeStateBoolListener(key: BoolProperty, cb: (value: Boolean) -> Unit) {
        stateBoolListeners[key]?.remove(cb)
    }

    override fun addPlayerStatusListener(cb: (value: PlayerStatus) -> Unit) {
        playerStatusListeners.add(cb)
    }

    override fun removePlayerStatusListener(cb: (value: PlayerStatus) -> Unit) {
        playerStatusListeners.remove(cb)
    }

    override fun addCastStatusListener(cb: (value: CastStatus) -> Unit) {
        castStatusListeners.add(cb)
    }

    override fun removeCastStatusListener(cb: (value: CastStatus) -> Unit) {
        castStatusListeners.remove(cb)
    }

    override fun addSourceListener(cb: (value: Source?) -> Unit) {
        sourceListeners.add(cb)
    }

    override fun removeSourceListener(cb: (value: Source?) -> Unit) {
        sourceListeners.remove(cb)
    }

    override fun addOnEndListener(cb: () -> Unit) {
        onEndListeners.add(cb)
    }

    override fun removeOnEndListener(cb: () -> Unit) {
        onEndListeners.remove(cb)
    }

    override fun addOnPrevListener(cb: () -> Unit) {
        onPrevListeners.add(cb)
    }

    override fun removeOnPrevListener(cb: () -> Unit) {
        onPrevListeners.remove(cb)
    }

    override fun addOnNextListener(cb: () -> Unit) {
        onNextListeners.add(cb)
    }

    override fun removeOnNextListener(cb: () -> Unit) {
        onNextListeners.remove(cb)
    }

    override fun addOnErrorListener(cb: (type: String, message: String) -> Unit) {
        onErrorListeners.add(cb)
    }

    override fun removeOnErrorListener(cb: (type: String, message: String) -> Unit) {
        onErrorListeners.remove(cb)
    }

    override fun addOnAudioFocusChangeListener(cb: (status: String) -> Unit) {
        onAudioFocusChangeListeners.add(cb)
    }

    override fun removeOnAudioFocusChangeListener(cb: (status: String) -> Unit) {
        onAudioFocusChangeListeners.remove(cb)
    }

    override fun addOnVideoTrackChangeListener(cb: (track: Track) -> Unit) {
        onVideoTrackChangeListeners.add(cb)
    }

    override fun removeOnVideoTrackChangeListener(cb: (track: Track) -> Unit) {
        onVideoTrackChangeListeners.remove(cb)
    }

    override fun addOnAudioTrackChangeListener(cb: (track: Track) -> Unit) {
        onAudioTrackChangeListeners.add(cb)
    }

    override fun removeOnAudioTrackChangeListener(cb: (track: Track) -> Unit) {
        onAudioTrackChangeListeners.remove(cb)
    }

    override fun addOnSubtitleChangeListener(cb: (track: Track?) -> Unit) {
        onSubtitleChangeListeners.add(cb)
    }

    override fun removeOnSubtitleChangeListener(cb: (track: Track?) -> Unit) {
        onSubtitleChangeListeners.remove(cb)
    }

    override fun addOnRenditionChangeListener(cb: (rendition: Rendition) -> Unit) {
        onRenditionChangeListeners.add(cb)
    }

    override fun removeOnRenditionChangeListener(cb: (rendition: Rendition) -> Unit) {
        onRenditionChangeListeners.remove(cb)
    }

    override fun addTracksListener(key: TrackProperty, cb: (value: Array<Track>) -> Unit) {
        tracksListeners.getOrPut(key) { mutableSetOf() }.add(cb)
    }

    override fun removeTracksListener(key: TrackProperty, cb: (value: Array<Track>) -> Unit) {
        tracksListeners[key]?.remove(cb)
    }

    override fun addRenditionsListener(cb: (value: Array<Rendition>) -> Unit) {
        renditionsListeners.add(cb)
    }

    override fun removeRenditionsListener(cb: (value: Array<Rendition>) -> Unit) {
        renditionsListeners.remove(cb)
    }

    override fun dispose() {
        player.removeListener(this)
        super.dispose()
    }
}
