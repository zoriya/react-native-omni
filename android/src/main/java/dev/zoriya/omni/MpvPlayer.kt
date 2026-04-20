package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jdtech.mpv.MPVLib

@SuppressLint("UnsafeOptInUsageError")
class MpvPlayer(ctx: Context) : SimpleBasePlayer(Looper.getMainLooper()), MPVLib.EventObserver {
    internal val mpv = (MPVLib.create(ctx) ?: throw Error("Failed to initialize MPVLib")).apply {
        setOptionString("vo", "gpu-next")
        setOptionString("force-window", "yes")
        setOptionString("gpu-context", "android")
        setOptionString("opengl-es", "yes")
        setOptionString("hwdec", "mediacodec-copy")
        setOptionString("profile", "fast")

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
        observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("speed", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        observeProperty("vid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_INT64)
    }

//    private data class TrackGroupBinding(val group: TrackGroup, val mpvIds: IntArray)

//    private var currentMediaItem: MediaItem? = null
//    private var currentTrackSelectionParameters: TrackSelectionParameters =
//        TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
//    private var trackGroupsByType: Map<Int, List<TrackGroupBinding>> = emptyMap()

    private val availableCommands: Player.Commands = Player.Commands.Builder()
        .add(COMMAND_PLAY_PAUSE)
        .add(COMMAND_STOP)
        .add(COMMAND_RELEASE)
        .add(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        .add(COMMAND_SEEK_BACK)
        .add(COMMAND_SEEK_FORWARD)
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

    override fun getState(): State {
        val positionMs = ((mpv.getPropertyDouble("time-pos") ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
        val cacheMs = ((mpv.getPropertyDouble("demuxer-cache-time") ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
        val durationMs = ((mpv.getPropertyDouble("duration") ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()

        val builder = State.Builder()
            .setAvailableCommands(availableCommands)
            .setPlayWhenReady(
                !(mpv.getPropertyBoolean("pause") ?: true),
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(getMpvPlaybackState())
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setIsLoading(mpv.getPropertyBoolean("paused-for-cache") ?: false)
            .setPlaybackParameters(PlaybackParameters((mpv.getPropertyDouble("speed") ?: 1.0).toFloat().coerceAtLeast(0f)))
            .setVolume((mpv.getPropertyDouble("volume") ?: 100.0).coerceIn(0.0, 100.0).toFloat() / 100f)
//            .setTrackSelectionParameters(currentTrackSelectionParameters)
            .setContentPositionMs(positionMs)
            .setContentBufferedPositionMs { cacheMs }
            .setTotalBufferedDurationMs { cacheMs }
//            .setPlaylistMetadata(currentMediaItem?.mediaMetadata ?: MediaMetadata.EMPTY)

//        val item = currentMediaItem
//        if (item != null) {
//            val itemBuilder = MediaItemData.Builder(item.mediaId)
//                .setMediaItem(item)
//                .setMediaMetadata(item.mediaMetadata)
//                .setIsSeekable(true)
//                .setTracks(buildTracks())
//
//            if (durationMs > 0L) {
//                itemBuilder.setDurationUs(durationMs * 1000L)
//            }
//
//            builder
//                .setPlaylist(listOf(itemBuilder.build()))
//                .setCurrentMediaItemIndex(0)
//        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        mpv.setPropertyBoolean("pause", !playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        mpv.setPropertyDouble("speed", playbackParameters.speed.coerceAtLeast(0f).toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != 0) {
            return Futures.immediateVoidFuture()
        }
        val targetMs = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        mpv.command(arrayOf("seek", (targetMs / 1000.0).toString(), "absolute"))
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        mpv.setPropertyDouble("volume", volume.coerceIn(0f, 1f) * 100.0)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is Surface -> {
                mpv.attachSurface(videoOutput)
                mpv.setOptionString("force-window", "yes")
                mpv.setOptionString("vo", "gpu-next")
            }

            is SurfaceHolder -> {
                val surface = videoOutput.surface
                if (surface != null && surface.isValid) {
                    mpv.attachSurface(surface)
                    mpv.setOptionString("force-window", "yes")
                    mpv.setOptionString("vo", "gpu-next")

                    val frame = videoOutput.surfaceFrame
                    val width = frame?.width() ?: 0
                    val height = frame?.height() ?: 0
                    if (width > 0 && height > 0) {
                        mpv.setPropertyString("android-surface-size", "${width}x${height}")
                    }
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        mpv.setOptionString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
        return Futures.immediateVoidFuture()
    }

//    override fun handleSetMediaItems(
//        mediaItems: List<MediaItem>,
//        startIndex: Int,
//        startPositionMs: Long
//    ): ListenableFuture<*> {
//        val target = when {
//            mediaItems.isEmpty() -> null
//            startIndex in mediaItems.indices -> mediaItems[startIndex]
//            else -> mediaItems[0]
//        }
//
//        currentMediaItem = target
//        trackGroupsByType = emptyMap()
//        mpv.command(arrayOf("stop"))
//
//        if (target != null) {
//            val uri = target.localConfiguration?.uri?.toString()
//            if (!uri.isNullOrEmpty()) {
//                val requestExtras = target.requestMetadata.extras
//                applyRequestHeaders(requestExtras)
//
//                val requestStartMs = requestExtras?.getLong(REQUEST_START_MS_KEY, C.TIME_UNSET) ?: C.TIME_UNSET
//                val start = if (startPositionMs != C.TIME_UNSET) startPositionMs else requestStartMs
//                if (start != C.TIME_UNSET && start >= 0L) {
//                    mpv.setPropertyDouble("start", start.toDouble() / 1000.0)
//                }
//
//                mpv.command(arrayOf("loadfile", uri, "replace"))
//                target.localConfiguration?.subtitleConfigurations?.forEach { subtitle ->
//                    mpv.command(arrayOf("sub-add", subtitle.uri.toString(), "cached"))
//                }
//            }
//        }
//
//        return Futures.immediateVoidFuture()
//    }

//    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
//        currentTrackSelectionParameters = trackSelectionParameters
//        applyTrackSelectionParameters(trackSelectionParameters)
//        return Futures.immediateVoidFuture()
//    }

    override fun handleStop(): ListenableFuture<*> {
        mpv.command(arrayOf("stop"))
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        mpv.removeObserver(this)
        mpv.detachSurface()
        mpv.destroy()
        return Futures.immediateVoidFuture()
    }

    override fun event(event: Int) {
//        trackGroupsByType = buildTrackGroupsByType()
        invalidateState()
    }

    override fun eventProperty(property: String) {
//        if (property == "vid" || property == "aid" || property == "sid") {
//            trackGroupsByType = buildTrackGroupsByType()
//        }
        invalidateState()
    }

    override fun eventProperty(property: String, value: Long) {
//        if (property == "vid" || property == "aid" || property == "sid") {
//            trackGroupsByType = buildTrackGroupsByType()
//        }
        invalidateState()
    }

    override fun eventProperty(property: String, value: Double) {
        invalidateState()
    }

    override fun eventProperty(property: String, value: Boolean) {
        invalidateState()
    }

    override fun eventProperty(property: String, value: String) {
        invalidateState()
    }

    private fun getMpvPlaybackState(): Int {
        val ended = mpv.getPropertyBoolean("eof-reached") ?: false
        if (ended) return STATE_ENDED

        val idle = mpv.getPropertyBoolean("core-idle") ?: false
        if (idle) return STATE_IDLE

        val loading = mpv.getPropertyBoolean("paused-for-cache") ?: false
        return if (loading) STATE_BUFFERING else STATE_READY
    }

//    private fun applyRequestHeaders(extras: Bundle?) {
//        if (extras == null) {
//            mpv.setPropertyString("http-header-fields", "")
//            return
//        }
//        val names = extras.getStringArrayList(REQUEST_HEADER_NAMES_KEY)
//        val values = extras.getStringArrayList(REQUEST_HEADER_VALUES_KEY)
//        if (names.isNullOrEmpty() || values.isNullOrEmpty()) {
//            mpv.setPropertyString("http-header-fields", "")
//            return
//        }
//        val size = minOf(names.size, values.size)
//        if (size <= 0) {
//            mpv.setPropertyString("http-header-fields", "")
//            return
//        }
//        val formatted = (0 until size).joinToString("\r\n") { idx -> "${names[idx]}: ${values[idx]}" }
//        mpv.setPropertyString("http-header-fields", formatted)
//    }

//    private fun buildTrackGroupsByType(): Map<Int, List<TrackGroupBinding>> {
//        val count = mpv.getPropertyInt("track-list/count") ?: 0
//        if (count <= 0) return emptyMap()
//
//        val grouped = LinkedHashMap<Int, MutableList<Pair<Int, Format>>>()
//        for (i in 0 until count) {
//            val base = "track-list/$i"
//            val type = when (mpv.getPropertyString("$base/type")) {
//                "video" -> C.TRACK_TYPE_VIDEO
//                "audio" -> C.TRACK_TYPE_AUDIO
//                "sub" -> C.TRACK_TYPE_TEXT
//                else -> C.TRACK_TYPE_UNKNOWN
//            }
//            if (type == C.TRACK_TYPE_UNKNOWN) continue
//
//            val id = mpv.getPropertyInt("$base/id") ?: continue
//            val label = mpv.getPropertyString("$base/title") ?: mpv.getPropertyString("$base/codec")
//            val language = mpv.getPropertyString("$base/lang")
//            val codec = mpv.getPropertyString("$base/codec")
//
//            val sampleMimeType = when (type) {
//                C.TRACK_TYPE_VIDEO -> MimeTypes.VIDEO_UNKNOWN
//                C.TRACK_TYPE_AUDIO -> MimeTypes.AUDIO_UNKNOWN
//                C.TRACK_TYPE_TEXT -> MimeTypes.TEXT_UNKNOWN
//                else -> null
//            }
//
//            val formatBuilder = Format.Builder()
//                .setId(id)
//                .setLabel(label)
//                .setLanguage(language)
//                .setCodecs(codec)
//            sampleMimeType?.let { formatBuilder.setSampleMimeType(it) }
//
//            grouped.getOrPut(type) { mutableListOf() }.add(id to formatBuilder.build())
//        }
//
//        val result = LinkedHashMap<Int, List<TrackGroupBinding>>()
//        for ((type, entries) in grouped) {
//            if (entries.isEmpty()) continue
//            val formats = entries.map { it.second }.toTypedArray()
//            val ids = entries.map { it.first }.toIntArray()
//            val group = TrackGroup("mpv-$type", *formats)
//            result[type] = listOf(TrackGroupBinding(group, ids))
//        }
//        return result
//    }

//    private fun buildTracks(): Tracks {
//        if (trackGroupsByType.isEmpty()) {
//            trackGroupsByType = buildTrackGroupsByType()
//        }
//        if (trackGroupsByType.isEmpty()) return Tracks.EMPTY
//
//        val selectedVideo = mpv.getPropertyInt("vid")
//        val selectedAudio = mpv.getPropertyInt("aid")
//        val selectedSubtitle = mpv.getPropertyInt("sid")
//
//        val groups = ArrayList<Tracks.Group>()
//        for ((type, bindings) in trackGroupsByType) {
//            val selectedId = when (type) {
//                C.TRACK_TYPE_VIDEO -> selectedVideo
//                C.TRACK_TYPE_AUDIO -> selectedAudio
//                C.TRACK_TYPE_TEXT -> selectedSubtitle
//                else -> null
//            }
//            for (binding in bindings) {
//                val selected = BooleanArray(binding.mpvIds.size) { idx ->
//                    val id = binding.mpvIds[idx]
//                    selectedId != null && selectedId == id
//                }
//                val support = IntArray(binding.mpvIds.size) { C.FORMAT_HANDLED }
//                groups.add(Tracks.Group(binding.group, false, support, selected))
//            }
//        }
//
//        return Tracks(groups)
//    }

//    private fun applyTrackSelectionParameters(parameters: TrackSelectionParameters) {
//        if (trackGroupsByType.isEmpty()) {
//            trackGroupsByType = buildTrackGroupsByType()
//        }
//
//        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)) {
//            mpv.setPropertyString("vid", "no")
//        }
//        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO)) {
//            mpv.setPropertyString("aid", "no")
//        }
//        if (parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
//            mpv.setPropertyString("sid", "no")
//        }
//
//        for (override in parameters.overrides.values) {
//            val binding = trackGroupsByType[override.getType()]
//                ?.firstOrNull { it.group == override.mediaTrackGroup }
//                ?: continue
//            val selectedIndex = override.trackIndices.firstOrNull() ?: continue
//            if (selectedIndex !in binding.mpvIds.indices) continue
//            val selectedMpvId = binding.mpvIds[selectedIndex]
//
//            when (override.getType()) {
//                C.TRACK_TYPE_VIDEO -> mpv.setPropertyInt("vid", selectedMpvId)
//                C.TRACK_TYPE_AUDIO -> mpv.setPropertyInt("aid", selectedMpvId)
//                C.TRACK_TYPE_TEXT -> mpv.setPropertyInt("sid", selectedMpvId)
//            }
//        }
//    }

//    companion object {
//        internal const val REQUEST_HEADER_NAMES_KEY = "omni.request.header_names"
//        internal const val REQUEST_HEADER_VALUES_KEY = "omni.request.header_values"
//        internal const val REQUEST_START_MS_KEY = "omni.request.start_ms"
//    }
}
