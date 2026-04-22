package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.margelo.nitro.NitroModules
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Source
import com.margelo.nitro.omni.Track
import androidx.core.net.toUri
import androidx.media3.common.MediaItem.RequestMetadata
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@SuppressLint("UnsafeOptInUsageError")
class OmniPlayer : HybridOmniPlayerSpec() {
    private val ctx = NitroModules.applicationContext ?: throw Error("No Context available!")
    val player = MpvPlayer(ctx)
    override val eventMap = EventMap(player)

    override var showNotification: Boolean? = false
        set(value) {
            Log.e("omni", "Toggle show notif, old: ${field}, new: ${value}")
            if (value == true) {
                if (notificationPlayer != null) {
                    throw Error("Two players can't display notifications at the same time.")
                }
                notificationPlayer = player
                ctx.startForegroundService(Intent(ctx, OmniPlayerService::class.java))
            } else if (field == true) {
                ctx.stopService(Intent(ctx, OmniPlayerService::class.java))
            }
            field = value
        }

    override fun dispose() {
        showNotification = false
        super.dispose()

        eventMap.dispose()
        player.release()
    }

    private var currentSource: Source? = null
    override var source: Source
        get() = currentSource
            ?: throw IllegalStateException("source should be initialized before get")
        set(value) {
            currentSource = value
            val src = source.src.firstOrNull() ?: return player.setMediaItem(MediaItem.EMPTY)
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
            player.setMediaItem(
                MediaItem.Builder()
                    .setUri(src.uri)
                    .setMimeType(src.mimeType)
                    .setMediaId(src.uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(value.metadata?.title)
                            .setAlbumTitle(value.metadata?.album)
                            .setArtist(value.metadata?.artist)
                            .apply {
                                value.metadata?.imageLink?.let { setArtworkUri(it.toUri()) }
                            }
                            .build())
                    .setSubtitleConfigurations(value.subtitles.map { subtitle ->
                        SubtitleConfiguration.Builder(subtitle.link.toUri())
                            .setId(subtitle.id)
                            .setLanguage(subtitle.language)
                            .setLabel(subtitle.label)
                            .setMimeType(subtitle.mimeType)
                            .build()
                    })
                    .setRequestMetadata(
                        RequestMetadata.Builder()
                            .setMediaUri(src.uri.toUri())
                            // .setExtras(headers)
                            .build()
                    )
                    .build()
            )
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
            Player.STATE_IDLE,
            Player.STATE_ENDED -> PlayerStatus.IDLE
            Player.STATE_BUFFERING -> PlayerStatus.LOADING
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
        player.seekToPreviousMediaItem()
    }

    override fun playNext() {
        player.seekToNextMediaItem()
    }

    override fun selectVideo(video: Track) {
        selectTrack(C.TRACK_TYPE_VIDEO, video)
    }

    override fun selectAudio(audio: Track) {
        selectTrack(C.TRACK_TYPE_AUDIO, audio)
    }

    override fun selectSubtitle(subtitle: Track?) {
        if (subtitle == null) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            return
        }
        selectTrack(C.TRACK_TYPE_TEXT, subtitle)
    }

    override fun selectRendition(rendition: Rendition?) {
    }

    private fun tracksByType(trackType: Int): Array<Track> {
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) return emptyArray()
//
        val result = ArrayList<Track>()
        for (group in groups) {
            val mediaGroup = group.mediaTrackGroup
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                result.add(
                    Track(
                        id = format.id ?: mediaGroup.id,
                        label = format.label,
                        language = format.language,
                        selected = group.isTrackSelected(i)
                    )
                )
            }
        }
        return result.toTypedArray()
    }

    private fun selectTrack(trackType: Int, track: Track) {
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        for (group in groups) {
            for (i in 0 until group.length) {
                val formatId = group.getTrackFormat(i).id ?: group.mediaTrackGroup.id
                if (formatId != track.id) continue

                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(trackType, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                return
            }
        }
    }

    companion object {
        var notificationPlayer: Player? = null
    }
}

@SuppressLint("UnsafeOptInUsageError")
class OmniPlayerService : MediaSessionService() {
    private val ctx = NitroModules.applicationContext ?: throw Error("No Context available!")
    private val player = OmniPlayer.notificationPlayer ?: throw Error("No player available")
    var mediaSession: MediaSession = MediaSession.Builder(ctx, player).build()

    init {
        Log.e("omni", "service inited")
    }

    override fun onCreate() {
        Log.e("omni", "service created")
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(ctx).build()
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    override fun getApplicationContext(): Context? {
        return NitroModules.applicationContext
    }
}
