package dev.zoriya.omni

import android.os.SystemClock
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
import androidx.media3.common.Tracks
import com.margelo.nitro.omni.BoolProperty
import com.margelo.nitro.omni.HybridOmniEventMapSpec
import com.margelo.nitro.omni.NumberProperty
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Track

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
    private var lastTimePosDispatchMs = 0L

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
    }

    override fun onPlayerError(error: PlaybackException) {
        onErrorListeners.forEach {
            it(error.errorCodeName, error.message ?: "unknown message")
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        emitCoreState(forceCurrentTime = true)
    }

    private fun emitCoreState(forceCurrentTime: Boolean) {
        emitCurrentTime(forceCurrentTime)
        stateListeners[NumberProperty.BUFFERED]?.forEach {
            it((player.totalBufferedDuration.toDouble() / 1000.0).coerceAtLeast(0.0))
        }
        stateListeners[NumberProperty.DURATION]?.forEach {
            val duration = player.duration
            it(if (duration == C.TIME_UNSET) 0.0 else (duration.toDouble() / 1000.0).coerceAtLeast(0.0))
        }
    }

    private fun emitCurrentTime(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastTimePosDispatchMs < 1000L) return
        lastTimePosDispatchMs = now
        stateListeners[NumberProperty.CURRENTTIME]?.forEach {
            it((player.currentPosition.toDouble() / 1000.0).coerceAtLeast(0.0))
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
