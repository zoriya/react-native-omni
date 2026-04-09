package dev.zoriya.omni

import com.margelo.nitro.omni.HybridOmniPlayerPropsSpec
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Track
import com.margelo.nitro.omni.Variant_NullType_Rendition

class OmniPlayer(source: HybridOmniPlayerPropsSpec) : HybridOmniPlayerSpec() {
    override val hasPrev: Boolean
        get() = TODO("Not yet implemented")
    override val hasNext: Boolean
        get() = TODO("Not yet implemented")
    override val status: PlayerStatus
        get() = TODO("Not yet implemented")
    override val isPlaying: Boolean
        get() = TODO("Not yet implemented")
    override var currentTime: Double
        get() = TODO("Not yet implemented")
        set(value) {}
    override val buffered: Double
        get() = TODO("Not yet implemented")
    override val duration: Double
        get() = TODO("Not yet implemented")
    override var playbackRate: Double
        get() = TODO("Not yet implemented")
        set(value) {}
    override var volume: Double
        get() = TODO("Not yet implemented")
        set(value) {}
    override val videos: Array<Track>
        get() = TODO("Not yet implemented")
    override val audios: Array<Track>
        get() = TODO("Not yet implemented")
    override val subtitles: Array<Track>
        get() = TODO("Not yet implemented")
    override val rendition: Array<Rendition>
        get() = TODO("Not yet implemented")

    override fun play() {
        TODO("Not yet implemented")
    }

    override fun pause() {
        TODO("Not yet implemented")
    }

    override fun seekBy(offset: Double) {
        TODO("Not yet implemented")
    }

    override fun playPrev() {
        TODO("Not yet implemented")
    }

    override fun playNext() {
        TODO("Not yet implemented")
    }

    override fun selectVideo(video: Track) {
        TODO("Not yet implemented")
    }

    override fun selectAudio(audio: Track) {
        TODO("Not yet implemented")
    }

    override fun selectSubtitle(subtitle: Track) {
        TODO("Not yet implemented")
    }

    override fun selectRendition(rendition: Variant_NullType_Rendition?) {
        TODO("Not yet implemented")
    }
}