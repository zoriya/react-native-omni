package dev.zoriya.omni

import com.margelo.nitro.omni.BoolProperty
import com.margelo.nitro.omni.HybridOmniEventMapSpec
import com.margelo.nitro.omni.NumberProperty
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Track
import android.os.SystemClock
import dev.jdtech.mpv.MPVLib

class EventMap(private val player: MPVLib) : HybridOmniEventMapSpec(), MPVLib.EventObserver {
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
    private var isSeeking = false
    private var eofReached = false
    private var lastTimePosDispatchMs = 0L

    init {
        player.addObserver(this)
        player.observeProperty("vid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        player.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        player.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        player.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        player.observeProperty("mute", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        player.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        player.observeProperty("core-idle", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        player.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        player.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        player.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        player.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        player.observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        player.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    }

    private fun computePlayerStatus(): PlayerStatus {
        val idle = player.getPropertyBoolean("core-idle") ?: false
        val loading = player.getPropertyBoolean("paused-for-cache") ?: false
        return when {
            idle -> PlayerStatus.IDLE
            loading -> PlayerStatus.LOADING
            else -> PlayerStatus.READYTOPLAY
        }
    }

    private fun getTrackById(type: String, id: Long): Track? {
        val count = player.getPropertyInt("track-list/count") ?: 0
        for (i in 0 until count) {
            val base = "track-list/$i"
            val trackType = player.getPropertyString("$base/type") ?: continue
            if (trackType != type) continue
            val trackId = player.getPropertyInt("$base/id") ?: continue
            if (trackId.toLong() != id) continue
            val selected = player.getPropertyBoolean("$base/selected") ?: false
            val label = player.getPropertyString("$base/title")
                ?: player.getPropertyString("$base/codec")
            val language = player.getPropertyString("$base/lang")
            return Track(
                id = trackId.toString(),
                label = label,
                language = language,
                selected = selected
            )
        }
        return null
    }

    override fun event(event: Int) {
        when (event) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE ->
                run {
                    eofReached = false
                    isSeeking = false
                    playerStatusListeners.forEach { it(PlayerStatus.LOADING) }
                }

            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED ->
                playerStatusListeners.forEach { it(PlayerStatus.READYTOPLAY) }

            MPVLib.MpvEvent.MPV_EVENT_SEEK -> isSeeking = true
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> isSeeking = false
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                isSeeking = false
                if (eofReached) {
                    onEndListeners.forEach { it() }
                } else {
                    onErrorListeners.forEach {
                        it("end_file", "playback ended before reaching EOF")
                    }
                }
                playerStatusListeners.forEach { it(PlayerStatus.IDLE) }
            }

            MPVLib.MpvEvent.MPV_EVENT_QUEUE_OVERFLOW -> onErrorListeners.forEach {
                it("queue_overflow", "mpv event queue overflow")
            }
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "vid" -> {
                val track = getTrackById("video", value)
                if (track != null) onVideoTrackChangeListeners.forEach { it(track) }
            }

            "aid" -> {
                val track = getTrackById("audio", value)
                if (track != null) onAudioTrackChangeListeners.forEach { it(track) }
            }

            "sid" -> {
                val track = getTrackById("sub", value)
                onSubtitleChangeListeners.forEach { it(track) }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> if (isSeeking || SystemClock.elapsedRealtime() - lastTimePosDispatchMs >= 1000L) {
                lastTimePosDispatchMs = SystemClock.elapsedRealtime()
                stateListeners[NumberProperty.CURRENTTIME]?.forEach { it(value.coerceAtLeast(0.0)) }
            }

            "demuxer-cache-time" -> stateListeners[NumberProperty.BUFFERED]
                ?.forEach { it(value.coerceAtLeast(0.0)) }

            "duration" -> stateListeners[NumberProperty.DURATION]
                ?.forEach { it(value.coerceAtLeast(0.0)) }

            "speed" -> stateListeners[NumberProperty.PLAYBACKRATE]
                ?.forEach { it(value) }

            "volume" -> stateListeners[NumberProperty.VOLUME]
                ?.forEach { it((value / 100.0).coerceIn(0.0, 1.0)) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                val isPlaying = !value
                onAudioFocusChangeListeners.forEach { it(if (isPlaying) "playing" else "paused") }
                stateBoolListeners[BoolProperty.ISPLAYING]?.forEach { it(isPlaying) }
            }

            "mute" ->
                stateBoolListeners[BoolProperty.MUTED]?.forEach { it(value) }

            "eof-reached" -> eofReached = value
            "core-idle", "paused-for-cache" ->
                playerStatusListeners.forEach { it(computePlayerStatus()) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "sid" && value == "no") {
            onSubtitleChangeListeners.forEach { it(null) }
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
        player.removeObserver(this)
        super.dispose()
    }
}
