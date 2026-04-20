package dev.zoriya.omni

import android.util.Log
import com.margelo.nitro.NitroModules
import com.margelo.nitro.omni.Source
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Track
import dev.jdtech.mpv.MPVLib
import dev.zoriya.omni.utils.deferredObservable

class OmniPlayer : HybridOmniPlayerSpec() {
    val ctx = NitroModules.applicationContext ?: throw Error("No Context available!")
    val player = MPVLib.create(ctx) ?: throw Error("Failed to initialize MPVLib")
    override val eventMap = EventMap(player)

    init {
        // `vo` is effectively fixed after init in libmpv.
        // Initializing with `null` keeps audio working but prevents video output forever.
        player.setOptionString("vo", "gpu-next")
        player.setOptionString("force-window", "yes")
        player.setOptionString("gpu-context", "android")
        player.setOptionString("opengl-es", "yes")
        player.setOptionString("hwdec", "mediacodec-copy")
        player.setOptionString("profile", "fast")

        player.setOptionString("cache", "yes")
        player.setOptionString("cache-pause-initial", "yes")
        player.setOptionString("demuxer-max-bytes", "150MiB")
        player.setOptionString("demuxer-max-back-bytes", "75MiB")
        player.setOptionString("demuxer-readahead-secs", "20")

        player.setOptionString("save-position-on-quit", "no")
        player.setOptionString("ytdl", "no")

        // seek to keyframes
        player.setOptionString("hr-seek", "no")

        player.init()
    }

    override fun dispose() {
        super.dispose()

        player.detachSurface()
        eventMap.dispose()
        player.destroy()
    }

    override var source: Source by deferredObservable { _, _, new ->
        Log.e("omni", "Chaning source")
        player.command(arrayOf("stop"))

        val src = new.src.firstOrNull() ?: return@deferredObservable

        player.setPropertyString(
            "http-header-fields",
            src.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
        )
        new.startTime?.let { start ->
            player.setPropertyDouble("start", start.coerceAtLeast(0.0))
        }
        // TODO: set video/audio/sub specified
        player.command(arrayOf("loadfile", src.uri, "replace"))
        for (subtitle in new.subtitles) {
            player.command(arrayOf("sub-add", subtitle.link, "cached"))
        }
    }

    fun setSurface(surface: android.view.Surface?) {
        if (surface == null) {
            player.setOptionString("vo", "null")
            player.setOptionString("force-window", "no")
            player.detachSurface()
        } else {
            player.attachSurface(surface)
            player.setOptionString("force-window", "yes")
            player.setOptionString("vo", "gpu-next")
        }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        player.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override val hasPrev get() = source.metadata?.hasPrev ?: false
    override val hasNext get() = source.metadata?.hasNext ?: false
    override val status: PlayerStatus
        get() {
            val idle = player.getPropertyBoolean("core-idle") ?: false
            val loading = player.getPropertyBoolean("paused-for-cache") ?: false
            return when {
                idle -> PlayerStatus.IDLE
                loading -> PlayerStatus.LOADING
                else -> PlayerStatus.READYTOPLAY
            }
        }
    override val isPlaying get() = !(player.getPropertyBoolean("pause") ?: true)
    override var currentTime
        get() = (player.getPropertyDouble("time-pos") ?: 0.0).coerceAtLeast(0.0)
        set(value) {
            player.command(arrayOf("seek", value.coerceAtLeast(0.0).toString(), "absolute"))
        }
    override val buffered
        get() = (player.getPropertyDouble("demuxer-cache-time") ?: 0.0).coerceAtLeast(0.0)
    override val duration get() = (player.getPropertyDouble("duration") ?: 0.0).coerceAtLeast(0.0)

    override var playbackRate
        get() = player.getPropertyDouble("speed") ?: 1.0
        set(value) {
            player.setPropertyDouble("speed", value.coerceAtLeast(0.0))
        }

    override var muted
        get() = player.getPropertyBoolean("mute") ?: false
        set(value) {
            player.setPropertyBoolean("mute", value)
        }
    override var volume
        get() = (player.getPropertyDouble("volume") ?: 100.0).coerceIn(0.0, 100.0) / 100.0
        set(value) {
            player.setPropertyDouble("volume", value.coerceIn(0.0, 1.0) * 100.0)
        }
    override val videos get() = getTracks("video")
    override val audios get() = getTracks("audio")
    override val subtitles get() = getTracks("sub")

    // TODO: use `edition-list`
    override val rendition: Array<Rendition> get() = emptyArray()

    override fun play() {
        player.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        player.setPropertyBoolean("pause", true)
    }

    override fun seekBy(offset: Double) {
        player.command(arrayOf("seek", offset.toString(), "relative"))
    }

    override fun playPrev() {
        eventMap.onPrevListeners.forEach { it() }
    }

    override fun playNext() {
        eventMap.onNextListeners.forEach { it() }
    }

    override fun selectVideo(video: Track) {
        val id = video.id.toIntOrNull() ?: return
        player.setPropertyInt("vid", id)
    }

    override fun selectAudio(audio: Track) {
        val id = audio.id.toIntOrNull() ?: return
        player.setPropertyInt("aid", id)
    }

    override fun selectSubtitle(subtitle: Track?) {
        when (subtitle) {
            null -> player.setPropertyString("sid", "no")
            else -> {
                val id = subtitle.id.toIntOrNull() ?: return
                player.setPropertyInt("sid", id)
            }
        }
    }

    override fun selectRendition(rendition: Rendition?) {
        // Not supported by libVLC MediaPlayer track APIs.
    }

    private fun getTracks(type: String): Array<Track> {
        val count = player.getPropertyInt("track-list/count") ?: 0
        if (count <= 0) return emptyArray()

        val tracks = ArrayList<Track>(count)
        for (i in 0 until count) {
            val base = "track-list/$i"
            val trackType = player.getPropertyString("$base/type") ?: continue
            if (trackType != type) continue

            val id = player.getPropertyInt("$base/id") ?: continue
            val selected = player.getPropertyBoolean("$base/selected") ?: false
            val label = player.getPropertyString("$base/title")
                ?: player.getPropertyString("$base/codec")
            val language = player.getPropertyString("$base/lang")

            tracks.add(
                Track(
                    id = id.toString(),
                    label = label,
                    language = language,
                    selected = selected
                )
            )
        }

        return tracks.toTypedArray()
    }
}
