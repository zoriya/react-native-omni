package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// exoplayer errors vlc can get past
private val VLC_ERRORS = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
)

// ass/ssa, under any of its mime types (text/x-ssa, text/x-ass...)
private fun isAss(mime: String?) = mime?.lowercase()?.let { "ass" in it || "ssa" in it } == true

private class Pick(val type: Int, val id: String, val position: Int, val trackIndices: List<Int>)

@SuppressLint("UnsafeOptInUsageError")
class AutoPlayer(
    private val ctx: Context,
    private val exo: ExoPlayer,
    private val onSwitch: () -> Unit,
) : ForwardingSimpleBasePlayer(exo) {
    private val handler = Handler(Looper.getMainLooper())

    // exoplayer gave up on the current media, vlc takes it over in a moment
    private var fallingBack = false
    private var handleAudioFocus = false
    private var videoOutput: SurfaceView? = null
    // the tracks picked on exoplayer, picked on vlc once it knows its own
    private var pendingPicks = emptyList<Pick>()
    private val vlc = lazy {
        VlcPlayer(ctx).also { vlc ->
            vlc.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != STATE_READY || pendingPicks.isEmpty()) return
                    val params = vlc.trackSelectionParameters.buildUpon()
                    for (pick in pendingPicks) {
                        val groups = vlc.currentTracks.groups.filter { it.type == pick.type }.map { it.mediaTrackGroup }
                        val group = groups.firstOrNull { it.id == pick.id } ?: groups.getOrNull(pick.position) ?: continue
                        val indices = pick.trackIndices.filter { it < group.length }.ifEmpty { listOf(0) }
                        params.setOverrideForType(TrackSelectionOverride(group, indices))
                    }
                    pendingPicks = emptyList()
                    vlc.trackSelectionParameters = params.build()
                }
            })
        }
    }

    val usesVlc: Boolean
        get() = player !== exo

    init {
        // exoplayer notifies these before onEvents, where the forwarded state is refreshed: its
        // error never reaches the app
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode in VLC_ERRORS) fallBack()
            }

            override fun onTracksChanged(tracks: Tracks) {
                val groups = tracks.groups
                fun playable(type: Int) = groups.none { it.type == type } || groups.any { it.type == type && it.isSupported }
                val hasAss = groups.any { group ->
                    val format = group.getTrackFormat(0)
                    group.type == C.TRACK_TYPE_TEXT && (isAss(format.sampleMimeType) || isAss(format.codecs))
                }
                if (!playable(C.TRACK_TYPE_VIDEO) || !playable(C.TRACK_TYPE_AUDIO) || hasAss) fallBack()
            }
        })
    }

    override fun getState(): State {
        val state = super.getState()
        if (!fallingBack) return state
        return state.buildUpon()
            .setPlayerError(null)
            .apply { if (!state.playlist.isEmpty()) setPlaybackState(STATE_BUFFERING) }
            .build()
    }

    private fun fallBack() {
        if (fallingBack || player !== exo) return
        fallingBack = true
        // exoplayer is still notifying its listeners, it is stopped after that
        handler.post {
            // a new media came meanwhile
            if (!fallingBack) return@post
            val item = exo.currentMediaItem
            val position = exo.currentPosition
            val picks = exo.trackSelectionParameters.overrides.values.mapNotNull { override ->
                val index = exo.currentTracks.groups
                    .filter { it.type == override.type }
                    .indexOfFirst { it.mediaTrackGroup == override.mediaTrackGroup }
                if (index < 0) return@mapNotNull null
                Pick(override.type, override.mediaTrackGroup.id, index, override.trackIndices)
            }
            switchTo(vlc.value, item, position)
            pendingPicks = picks
            fallingBack = false
            // exoplayer keeps its error until its next prepare, the next media would report it
            exo.prepare()
        }
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<*> {
        fallingBack = false
        pendingPicks = emptyList()
        val item = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
        // rfc 6381 codecs or mime types
        val formats = item?.requestMetadata?.extras?.getString(OmniPlayer.CODECS_EXTRA).orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { codec ->
                if ('/' in codec) {
                    Format.Builder().setSampleMimeType(codec).build()
                } else {
                    val mime = MimeTypes.getMediaMimeType(codec) ?: return@mapNotNull null
                    Format.Builder().setSampleMimeType(mime).setCodecs(codec).build()
                }
            }
        fun playable(isType: (String?) -> Boolean): Boolean {
            val ofType = formats.filter { isType(it.sampleMimeType) }
            return ofType.isEmpty() || ofType.any { format ->
                val passthrough = MimeTypes.isAudio(format.sampleMimeType) &&
                    AudioCapabilities.getCapabilities(ctx, exo.audioAttributes, null, emptyList())
                        .isPassthroughPlaybackSupported(format, exo.audioAttributes)
                passthrough || try {
                    MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false)
                        .any { it.isFormatSupported(ctx, format) }
                } catch (_: MediaCodecUtil.DecoderQueryException) {
                    true
                }
            }
        }
        val hasAss = item?.localConfiguration?.subtitleConfigurations.orEmpty().any { isAss(it.mimeType) } ||
            formats.any { isAss(it.sampleMimeType) }
        val exoCanPlay = !hasAss && playable(MimeTypes::isVideo) && playable(MimeTypes::isAudio)
        val target = if (exoCanPlay) exo else vlc.value
        if (target !== player) switchTo(target)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    private fun switchTo(target: Player, item: MediaItem? = null, positionMs: Long = 0L) {
        val current = player
        current.stop()
        current.clearMediaItems()
        target.setAudioAttributes(current.audioAttributes, handleAudioFocus)
        target.volume = current.volume
        target.playbackParameters = current.playbackParameters
        // overrides point to the tracks of the current player, see `pendingPicks`
        target.trackSelectionParameters = current.trackSelectionParameters.buildUpon().clearOverrides().build()
        target.playWhenReady = current.playWhenReady
        videoOutput?.let {
            current.clearVideoSurfaceView(it)
            target.setVideoSurfaceView(it)
        }
        if (item != null) {
            target.setMediaItem(item, positionMs)
            target.prepare()
        }
        setPlayer(target)
        onSwitch()
    }

    override fun handleSetAudioAttributes(
        audioAttributes: AudioAttributes,
        handleAudioFocus: Boolean
    ): ListenableFuture<*> {
        this.handleAudioFocus = handleAudioFocus
        return super.handleSetAudioAttributes(audioAttributes, handleAudioFocus)
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        this.videoOutput = videoOutput as? SurfaceView
        return super.handleSetVideoOutput(videoOutput)
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput === this.videoOutput) this.videoOutput = null
        return super.handleClearVideoOutput(videoOutput)
    }

    override fun handleRelease(): ListenableFuture<*> {
        handler.removeCallbacksAndMessages(null)
        exo.release()
        if (vlc.isInitialized()) vlc.value.release()
        return Futures.immediateVoidFuture()
    }
}
