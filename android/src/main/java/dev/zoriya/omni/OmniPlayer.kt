package dev.zoriya.omni

import android.R
import android.annotation.SuppressLint
import android.app.PendingIntent
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.zoriya.omni.utils.ThreadHelper.mainThreadProperty
import dev.zoriya.omni.utils.ThreadHelper.runOnMainThread
import dev.zoriya.omni.utils.ThreadHelper.runOnMainThreadSync

@SuppressLint("UnsafeOptInUsageError")
class OmniPlayer : HybridOmniPlayerSpec() {
    private val ctx = NitroModules.applicationContext ?: throw Error("No Context available!")
    val player: Player = runOnMainThreadSync {
//        ExoPlayer.Builder(ctx).build()
         MpvPlayer(ctx)
    }
    override val eventMap = EventMap(player)

    override var showNotification: Boolean? = false
        set(value) {
            Log.e("omni", "Toggle show notif, old: ${field}, new: $value")
            if (value == true) {
                if (notificationPlayer != null) {
                    throw Error("Two players can't display notifications at the same time.")
                }
                notificationPlayer = player
                ctx.startForegroundService(Intent(ctx, OmniPlayerService::class.java))
            } else if (field == true && notificationPlayer == player) {
                ctx.stopService(Intent(ctx, OmniPlayerService::class.java))
                notificationPlayer = null
            }
            field = value
        }

    override fun dispose() {
        showNotification = false
        super.dispose()

        eventMap.dispose()
        runOnMainThread { player.release() }
    }

    private var currentSource: Source? = null
    override var source: Source
        get() = currentSource
            ?: throw IllegalStateException("source should be initialized before get")
        set(value) {
            Log.e("omni", "update source")
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

            val item = MediaItem.Builder()
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
            runOnMainThreadSync {
                player.setMediaItem(item)
                player.prepare()
            }
        }

    fun setSurface(holder: SurfaceHolder?) {
        runOnMainThread {
            if (holder == null) {
                player.clearVideoSurface()
            } else {
                player.setVideoSurfaceHolder(holder)
            }
        }
    }

    fun updateSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        runOnMainThread {
            (player as? MpvPlayer)?.updateSurfaceSize(width, height)
        }
    }

    override val hasPrev by mainThreadProperty { player.hasPreviousMediaItem() }
    override val hasNext by mainThreadProperty { player.hasNextMediaItem() }
    override val status by mainThreadProperty {
        when (player.playbackState) {
            Player.STATE_IDLE,
            Player.STATE_ENDED -> PlayerStatus.IDLE
            Player.STATE_BUFFERING -> PlayerStatus.LOADING
            else -> PlayerStatus.READYTOPLAY
        }
    }

    override val isPlaying by mainThreadProperty { player.isPlaying }
    override var currentTime by mainThreadProperty(
        get = { player.currentPosition.toDouble() / 1000.0 },
        set = { value -> player.seekTo((value.coerceAtLeast(0.0) * 1000.0).toLong()) }
    )

    override val buffered by mainThreadProperty {
        (player.totalBufferedDuration.toDouble() / 1000.0).coerceAtLeast(0.0)
    }
    override val duration by mainThreadProperty {
        if (player.duration == C.TIME_UNSET) 0.0 else (player.duration.toDouble() / 1000.0).coerceAtLeast(
            0.0
        )
    }

    override var playbackRate by mainThreadProperty(
        get = { player.playbackParameters.speed.toDouble() },
        set = { value -> player.setPlaybackSpeed(value.toFloat().coerceAtLeast(0f)) }
    )

    var _muted = false
    override var muted by mainThreadProperty(
        get = { _muted },
        set = { value -> if (value) player.mute() else player.unmute() }
    )

    override var volume by mainThreadProperty(
        get = { player.volume.toDouble() },
        set = { value -> player.volume = value.toFloat().coerceIn(0f, 1f) }
    )

    override val videos by mainThreadProperty { tracksByType(C.TRACK_TYPE_VIDEO) }
    override val audios by mainThreadProperty { tracksByType(C.TRACK_TYPE_AUDIO) }
    override val subtitles by mainThreadProperty { tracksByType(C.TRACK_TYPE_TEXT) }
    override val rendition: Array<Rendition> get() = emptyArray()

    override fun play() {
        runOnMainThreadSync { player.play() }
    }

    override fun pause() {
        runOnMainThreadSync { player.pause() }
    }

    override fun seekBy(offset: Double) {
        runOnMainThreadSync {
            val target = (player.currentPosition.toDouble() / 1000.0) + offset
            player.seekTo((target.coerceAtLeast(0.0) * 1000.0).toLong())
        }
    }

    override fun playPrev() {
        runOnMainThreadSync { player.seekToPreviousMediaItem() }
    }

    override fun playNext() {
        runOnMainThreadSync { player.seekToNextMediaItem() }
    }

    override fun selectVideo(video: Track) {
        runOnMainThreadSync { selectTrack(C.TRACK_TYPE_VIDEO, video) }
    }

    override fun selectAudio(audio: Track) {
        runOnMainThreadSync { selectTrack(C.TRACK_TYPE_AUDIO, audio) }
    }

    override fun selectSubtitle(subtitle: Track?) {
        runOnMainThreadSync {
            if (subtitle == null) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            } else {
                selectTrack(C.TRACK_TYPE_TEXT, subtitle)
            }
        }
    }

    override fun selectRendition(rendition: Rendition?) {
    }

    private fun tracksByType(trackType: Int): Array<Track> {
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) return emptyArray()

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
    lateinit var player: Player
    lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        player = OmniPlayer.notificationPlayer ?: throw Error("No player available")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val sessionActivity = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        mediaSession = MediaSession.Builder(this, player)
            .apply {
                sessionActivity?.let { setSessionActivity(it) }
            }
            .build()

        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: R.drawable.ic_media_play)
        })
        addSession(mediaSession)
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        triggerNotificationUpdate()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
