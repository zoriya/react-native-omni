package dev.zoriya.omni

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import com.margelo.nitro.omni.BoolProperty
import com.margelo.nitro.omni.HybridOmniEventMapSpec
import com.margelo.nitro.omni.NumberProperty
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Track

class EventMap(private val player: Player) : HybridOmniEventMapSpec(), Player.Listener {
    val onPrevListeners = mutableSetOf<() -> Unit>()
    val onNextListeners = mutableSetOf<() -> Unit>()
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
        emitCoreState(forceCurrentTime = true)
    }

    private fun computePlayerStatus(): PlayerStatus {
        val loading = player.isLoading
        val idle = player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED
        return when {
            loading -> PlayerStatus.LOADING
            idle -> PlayerStatus.IDLE
            else -> PlayerStatus.READYTOPLAY
        }
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

    override fun onEvents(player: Player, events: Player.Events) {
        if (
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            events.contains(Player.EVENT_IS_LOADING_CHANGED)
        ) {
            val status = computePlayerStatus()
            playerStatusListeners.forEach { it(status) }
            if (player.playbackState == Player.STATE_ENDED) {
                onEndListeners.forEach { it() }
            }
        }

        if (
            events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) ||
            events.contains(Player.EVENT_IS_PLAYING_CHANGED)
        ) {
            val isPlaying = player.isPlaying
            onAudioFocusChangeListeners.forEach { it(if (isPlaying) "playing" else "paused") }
            stateBoolListeners[BoolProperty.ISPLAYING]?.forEach { it(isPlaying) }
        }

        if (events.contains(Player.EVENT_VOLUME_CHANGED)) {
            val volume = player.volume.toDouble().coerceIn(0.0, 1.0)
            stateListeners[NumberProperty.VOLUME]?.forEach { it(volume) }
            stateBoolListeners[BoolProperty.MUTED]?.forEach { it(volume <= 0.0) }
        }

        if (events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
            stateListeners[NumberProperty.PLAYBACKRATE]?.forEach {
                it(player.playbackParameters.speed.toDouble())
            }
        }

        if (
            events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
            events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            events.contains(Player.EVENT_IS_LOADING_CHANGED)
        ) {
            emitCoreState(forceCurrentTime = true)
        } else {
            emitCoreState(forceCurrentTime = false)
        }

        if (events.contains(Player.EVENT_TRACKS_CHANGED)) {
            selectedTrack(C.TRACK_TYPE_VIDEO)?.let { track ->
                onVideoTrackChangeListeners.forEach { it(track) }
            }
            selectedTrack(C.TRACK_TYPE_AUDIO)?.let { track ->
                onAudioTrackChangeListeners.forEach { it(track) }
            }
            onSubtitleChangeListeners.forEach { it(selectedTrack(C.TRACK_TYPE_TEXT)) }
        }
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        onErrorListeners.forEach {
            it(error.errorCodeName, error.message ?: "unknown message")
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        emitCurrentTime(force = true)
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
