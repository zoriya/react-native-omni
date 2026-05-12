package dev.zoriya.omni

import android.annotation.SuppressLint
import androidx.media3.common.C
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
import androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import com.margelo.nitro.omni.BoolProperty
import com.margelo.nitro.omni.HybridOmniEventMapSpec
import com.margelo.nitro.omni.NumberProperty
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Track

@SuppressLint("UnsafeOptInUsageError")
class EventMap(private val player: Player) : HybridOmniEventMapSpec(), Player.Listener {
    private val onPrevListeners = mutableSetOf<() -> Unit>()
    private val onNextListeners = mutableSetOf<() -> Unit>()
    private val onEndListeners = mutableSetOf<() -> Unit>()
    private val onErrorListeners = mutableSetOf<(type: String, message: String) -> Unit>()
    private val onAudioFocusChangeListeners = mutableSetOf<(status: String) -> Unit>()
    private val onVideoTrackChangeListeners = mutableSetOf<(track: Track) -> Unit>()
    private val onAudioTrackChangeListeners = mutableSetOf<(track: Track) -> Unit>()
    private val onSubtitleChangeListeners = mutableSetOf<(track: Track?) -> Unit>()
    private val onRenditionChangeListeners = mutableSetOf<(rendition: Rendition) -> Unit>()
    private val stateListeners = mutableMapOf<NumberProperty, MutableSet<(Double) -> Unit>>()
    private val stateBoolListeners = mutableMapOf<BoolProperty, MutableSet<(Boolean) -> Unit>>()
    private val playerStatusListeners = mutableSetOf<(PlayerStatus) -> Unit>()

    private var lastMediaItemIndex = 0
    private var lastRendition: Rendition? = null
    private var lastIsAutoQuality: Boolean? = null

    init {
        player.addListener(this)
    }

    private fun selectedTrack(trackType: Int): Track? {
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                return Track(
                    id = format.id ?: mediaGroup.id,
                    label = format.label,
                    language = format.language,
                    selected = true
                )
            }
        }
        return null
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

    private fun emitRenditionChange() {
        val rendition = getCurrentRendition() ?: return
        if (rendition == lastRendition) return
        lastRendition = rendition
        onRenditionChangeListeners.forEach { it(rendition) }
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
        onIsPlayingChanged(player.isPlaying)
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

    override fun onTracksChanged(tracks: Tracks) {
        selectedTrack(TRACK_TYPE_VIDEO)?.let { track ->
            onVideoTrackChangeListeners.forEach { it(track) }
        }
        selectedTrack(TRACK_TYPE_AUDIO)?.let { track ->
            onAudioTrackChangeListeners.forEach { it(track) }
        }
        onSubtitleChangeListeners.forEach {
            it(
                selectedTrack(
                    TRACK_TYPE_TEXT
                )
            )
        }
        emitIsAutoQualityChange()
        emitRenditionChange()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        emitIsAutoQualityChange()
        emitRenditionChange()
    }

    override fun onPlayerError(error: PlaybackException) {
        onErrorListeners.forEach {
            it(error.errorCodeName, error.message ?: "unknown message")
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason != MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != MEDIA_ITEM_TRANSITION_REASON_SEEK) {
            lastMediaItemIndex = player.currentMediaItemIndex
            return
        }
        val newIndex = player.currentMediaItemIndex
        if (newIndex < lastMediaItemIndex) {
            onPrevListeners.forEach { it() }
        } else if (newIndex > lastMediaItemIndex) {
            onNextListeners.forEach { it() }
        }
        lastMediaItemIndex = newIndex
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
            it(if (duration == C.TIME_UNSET) 0.0 else (duration.toDouble() / 1000.0).coerceAtLeast(0.0))
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

    override fun dispose() {
        player.removeListener(this)
        super.dispose()
    }
}
