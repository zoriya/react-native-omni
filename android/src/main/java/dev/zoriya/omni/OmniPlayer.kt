package dev.zoriya.omni

import android.net.Uri
import com.margelo.nitro.NitroModules
import com.margelo.nitro.omni.HybridOmniPlayerPropsSpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Track
import dev.zoriya.omni.utils.deferredObservable
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

class OmniPlayer() : HybridOmniPlayerSpec() {
    val vlc = LibVLC(NitroModules.applicationContext ?: throw Error("No Context available!"))
    val player = MediaPlayer(vlc)

    var source: HybridOmniPlayerPropsSpec by deferredObservable { _, _, new ->
        val src = new.src.firstOrNull()?.uri ?: return@deferredObservable
        player.media = Media(vlc, Uri.parse(src))
        new.startTime?.let { start ->
            player.time = (start * 1000.0).toLong().coerceAtLeast(0L)
        }
    }

    override val hasPrev get() = source.metadata?.hasPrev ?: false
    override val hasNext get() = source.metadata?.hasNext ?: false
    override val status
        get() = when (player.playerState) {
            IMedia.State.NothingSpecial -> PlayerStatus.IDLE
            IMedia.State.Opening -> PlayerStatus.LOADING
            IMedia.State.Playing -> PlayerStatus.READYTOPLAY
            IMedia.State.Paused -> PlayerStatus.READYTOPLAY
            IMedia.State.Stopped -> PlayerStatus.IDLE
            IMedia.State.Ended -> PlayerStatus.IDLE
            IMedia.State.Error -> PlayerStatus.ERROR
            else -> PlayerStatus.ERROR
        }
    override val isPlaying: Boolean get() = player.isPlaying
    override var currentTime: Double
        get() = player.time.coerceAtLeast(0L) / 1000.0
        set(value) {
            player.time = (value * 1000.0).toLong().coerceAtLeast(0L)
        }
    override val buffered: Double
        get() {
            val total = duration
            if (total <= 0.0) return 0.0
            return (total * player.position.toDouble()).coerceIn(0.0, total)
        }
    override val duration: Double get() = player.length.coerceAtLeast(0L) / 1000.0
    override var playbackRate: Double
        get() = player.rate.toDouble()
        set(value) {
            if (value > 0.0) player.rate = value.toFloat()
        }
    override var volume: Double
        get() = (player.volume.coerceIn(0, 100) / 100.0)
        set(value) {
            player.volume = (value.coerceIn(0.0, 1.0) * 100.0).toInt()
        }
    override val videos get() = getTracks(IMedia.Track.Type.Video)
    override val audios get() = getTracks(IMedia.Track.Type.Audio)
    override val subtitles get() = getTracks(IMedia.Track.Type.Text)

    fun getTracks(type: Int): Array<Track> {
        val tracks = player.getTracks(type)
        val selected = player.getSelectedTrack(type)
        return tracks.map {
            Track(
                id = it.id,
                label = it.name,
                language = it.language,
                selected = it.id == selected.id
            )
        }.toTypedArray()
    }

    override val rendition: Array<Rendition> get() = emptyArray()

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekBy(offset: Double) {
        currentTime += offset
    }

    override fun playPrev() {
        // App-level action; no default implementation in VLC player.
    }

    override fun playNext() {
        // App-level action; no default implementation in VLC player.
    }

    override fun selectVideo(video: Track) {
        player.selectTrack(video.id)
    }

    override fun selectAudio(audio: Track) {
        player.selectTrack(audio.id)
    }

    override fun selectSubtitle(subtitle: Track?) {
        when (subtitle) {
            null -> player.unselectTrackType(IMedia.Track.Type.Text)
            else -> player.selectTrack(subtitle.id)
        }
    }

    override fun selectRendition(rendition: Rendition?) {
        // Not supported by libVLC MediaPlayer track APIs.
    }
}
