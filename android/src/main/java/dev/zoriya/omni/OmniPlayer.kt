package dev.zoriya.omni

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import com.margelo.nitro.NitroModules
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Source
import com.margelo.nitro.omni.Track
import java.util.ArrayList

@SuppressLint("UnsafeOptInUsageError")
class OmniPlayer : HybridOmniPlayerSpec() {
    private val ctx = NitroModules.applicationContext ?: throw Error("No Context available!")
    val player = MpvPlayer(ctx)
    override val eventMap = EventMap(player.mpv)
    private var currentSource: Source? = null

    override fun dispose() {
        super.dispose()

        eventMap.dispose()
        player.release()
    }

    override var source: Source
        get() = currentSource
            ?: throw IllegalStateException("source should be initialized before get")
        set(value) {
            currentSource = value
            player.setMediaItem(buildMediaItem(value))
        }

    fun setSurface(holder: SurfaceHolder?) {
        if (holder == null) {
            player.clearVideoSurface()
        } else {
            player.setVideoSurfaceHolder(holder)
        }
    }

    override val hasPrev get() = player.hasPreviousMediaItem()
    override val hasNext get() = player.hasNextMediaItem()
    override val status: PlayerStatus
        get() = when (player.playbackState) {
            androidx.media3.common.Player.STATE_IDLE,
            androidx.media3.common.Player.STATE_ENDED -> PlayerStatus.IDLE

            androidx.media3.common.Player.STATE_BUFFERING -> PlayerStatus.LOADING
            else -> PlayerStatus.READYTOPLAY
        }

    override val isPlaying get() = player.isPlaying
    override var currentTime
        get() = player.currentPosition.toDouble() / 1000.0
        set(value) {
            player.seekTo((value.coerceAtLeast(0.0) * 1000.0).toLong())
        }
    override val buffered
        get() = (player.totalBufferedDuration.toDouble() / 1000.0).coerceAtLeast(0.0)
    override val duration
        get() = if (player.duration == C.TIME_UNSET) 0.0 else (player.duration.toDouble() / 1000.0).coerceAtLeast(
            0.0
        )

    override var playbackRate
        get() = player.playbackParameters.speed.toDouble()
        set(value) {
            player.setPlaybackSpeed(value.toFloat().coerceAtLeast(0f))
        }

    var volumeMuted: Float? = null
    override var muted: Boolean
        get() = player.volume == 0.0f && volumeMuted != null
        set(value) {
            if (value) {
                volumeMuted = player.volume
                player.volume = 0.0f
            } else {
                player.volume = volumeMuted ?: 100.0f
                volumeMuted = null
            }
        }

    override var volume
        get() = player.volume.toDouble()
        set(value) {
            player.volume = value.toFloat().coerceIn(0f, 1f)
        }

    override val videos get() = tracksByType(C.TRACK_TYPE_VIDEO)
    override val audios get() = tracksByType(C.TRACK_TYPE_AUDIO)
    override val subtitles get() = tracksByType(C.TRACK_TYPE_TEXT)
    override val rendition: Array<Rendition> get() = emptyArray()

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekBy(offset: Double) {
        val target = (player.currentPosition.toDouble() / 1000.0) + offset
        player.seekTo((target.coerceAtLeast(0.0) * 1000.0).toLong())
    }

    override fun playPrev() {
        eventMap.onPrevListeners.forEach { it() }
    }

    override fun playNext() {
        eventMap.onNextListeners.forEach { it() }
    }

    override fun selectVideo(video: Track) {
        selectTrack(C.TRACK_TYPE_VIDEO, video)
    }

    override fun selectAudio(audio: Track) {
        selectTrack(C.TRACK_TYPE_AUDIO, audio)
    }

    override fun selectSubtitle(subtitle: Track?) {
//        if (subtitle == null) {
//            val params = player.trackSelectionParameters
//                .buildUpon()
//                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
//                .build()
//            player.trackSelectionParameters = params
//            return
//        }
//
//        val params = player.trackSelectionParameters
//            .buildUpon()
//            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
//            .build()
//        player.trackSelectionParameters = params
//        selectTrack(C.TRACK_TYPE_TEXT, subtitle)
    }

    override fun selectRendition(rendition: Rendition?) {
    }

    private fun buildMediaItem(source: Source): MediaItem {
        val src = source.src.firstOrNull() ?: return MediaItem.EMPTY

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(source.metadata?.title)
            .setAlbumTitle(source.metadata?.album)
            .setArtist(source.metadata?.artist)
            .apply {
                source.metadata?.imageLink?.let { setArtworkUri(android.net.Uri.parse(it)) }
            }
            .build()

//        val subtitleConfigurations = source.subtitles.map { subtitle ->
//            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.link))
//                .setId(subtitle.id)
//                .setLanguage(subtitle.language)
//                .setLabel(subtitle.label)
//                .setMimeType(subtitle.mimeType)
//                .build()
//        }

//        val headers = Bundle().apply {
//            putStringArrayList(
//                MpvPlayer.REQUEST_HEADER_NAMES_KEY,
//                ArrayList(src.headers.keys)
//            )
//            putStringArrayList(
//                MpvPlayer.REQUEST_HEADER_VALUES_KEY,
//                ArrayList(src.headers.values)
//            )
//            source.startTime?.let {
//                putLong(MpvPlayer.REQUEST_START_MS_KEY, (it.coerceAtLeast(0.0) * 1000.0).toLong())
//            }
//        }

        val requestMetadata = MediaItem.RequestMetadata.Builder()
            .setMediaUri(android.net.Uri.parse(src.uri))
//            .setExtras(headers)
            .build()

        val itemBuilder = MediaItem.Builder()
            .setUri(src.uri)
            .setMimeType(src.mimeType)
            .setMediaId(src.uri)
            .setMediaMetadata(mediaMetadata)
//            .setSubtitleConfigurations(subtitleConfigurations)
            .setRequestMetadata(requestMetadata)

        return itemBuilder.build()
    }

    private fun tracksByType(trackType: Int): Array<Track> {
        return emptyArray()
//        val groups = player.currentTracks.groups.filter { it.type == trackType }
//        if (groups.isEmpty()) return emptyArray()
//
//        val result = ArrayList<Track>()
//        for (group in groups) {
//            val mediaGroup = group.mediaTrackGroup
//            for (i in 0 until group.length) {
//                val format = group.getTrackFormat(i)
//                result.add(
//                    Track(
//                        id = format.id ?: mediaGroup.id,
//                        label = format.label,
//                        language = format.language,
//                        selected = group.isTrackSelected(i)
//                    )
//                )
//            }
//        }
//        return result.toTypedArray()
    }

    private fun selectTrack(trackType: Int, track: Track) {
        return
//        val targetId = track.id
//        val groups = player.currentTracks.groups.filter { it.type == trackType }
//        for (group in groups) {
//            val mediaGroup = group.mediaTrackGroup
//            for (i in 0 until group.length) {
//                val formatId = group.getTrackFormat(i).id ?: mediaGroup.id
//                if (formatId != targetId) continue
//
//                val override = TrackSelectionOverride(mediaGroup, i)
//                val builder: TrackSelectionParameters.Builder = player.trackSelectionParameters
//                    .buildUpon()
//                    .setTrackTypeDisabled(trackType, false)
//                    .setOverrideForType(override)
//                player.trackSelectionParameters = builder.build()
//                return
//            }
//        }
    }

}
