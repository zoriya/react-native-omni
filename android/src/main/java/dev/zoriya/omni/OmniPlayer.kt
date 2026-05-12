package dev.zoriya.omni

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
        // ExoPlayer.Builder(ctx).build()
        MpvPlayer(ctx)
    }
    override val eventMap = EventMap(player)

    override var showNotification: Boolean? = false
        set(value) {
            if (value == true) {
                if (notificationPlayer != null && notificationPlayer?.isPlaying == true) {
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
            Log.i("omni", "update source")
            currentSource = value
            val src = value.src.firstOrNull()
            if (src == null) {
                runOnMainThreadSync {
                    player.setMediaItem(MediaItem.EMPTY)
                    player.prepare()
                }
                return
            }

            val currentItem = buildMediaItem(src, value.metadata, value.subtitles)
            val mediaItems = mutableListOf<MediaItem>()

            if (value.metadata?.hasPrev == true) {
                mediaItems.add(currentItem)
            }

            mediaItems.add(currentItem)

            if (value.metadata?.hasNext == true) {
                mediaItems.add(currentItem)
            }

            runOnMainThreadSync {
                val startIndex = if (value.metadata?.hasPrev == true) 1 else 0
                val startPositionMs = (value.startTime?.coerceAtLeast(0.0) ?: 0.0) * 1000.0
                player.setMediaItems(mediaItems, startIndex, startPositionMs.toLong())
                player.prepare()
            }
        }

    private fun buildMediaItem(
        src: com.margelo.nitro.omni.VideoSrc,
        metadata: com.margelo.nitro.omni.Metadata?,
        subtitles: Array<com.margelo.nitro.omni.Subtitle>
    ): MediaItem {
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
        return MediaItem.Builder()
            .setUri(src.uri)
            .setMimeType(src.mimeType)
            .setMediaId(src.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(metadata?.title)
                    .setAlbumTitle(metadata?.album)
                    .setArtist(metadata?.artist)
                    .apply {
                        metadata?.imageLink?.let { setArtworkUri(it.toUri()) }
                    }
                    .build())
            .setSubtitleConfigurations(subtitles.map { subtitle ->
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

    override val hasPrev: Boolean get() = currentSource?.metadata?.hasPrev == true
    override val hasNext: Boolean get() = currentSource?.metadata?.hasNext == true
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
    override val rendition by mainThreadProperty { getRenditions() }

    override var isAutoQuality by mainThreadProperty {
        player.trackSelectionParameters.overrides.none {
            it.key.type == C.TRACK_TYPE_VIDEO
        }
    }

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
        runOnMainThreadSync {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setPreferredVideoLanguage(video.language)
                .setPreferredVideoLabels(*(video.label?.let { arrayOf(it) } ?: emptyArray()))
                .build()
        }
    }

    override fun selectAudio(audio: Track) {
        runOnMainThreadSync {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage(audio.language)
                .setPreferredAudioLabels(*(audio.label?.let { arrayOf(it) } ?: emptyArray()))
                .build()
        }
    }

    override fun selectSubtitle(subtitle: Track?) {
        runOnMainThreadSync {
            if (subtitle == null) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            } else {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setPreferredTextLanguage(subtitle.language)
                    .setPreferredTextLabels(*(subtitle.label?.let { arrayOf(it) } ?: emptyArray()))
                    .build()
            }
        }
    }

    private fun tracksByType(trackType: Int): Array<Track> {
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) return emptyArray()

        return groups.map {
            it.getTrackFormat(0).run {
                Track(
                    id = it.mediaTrackGroup.id,
                    label = this.label,
                    language = this.language,
                    selected = it.isSelected
                )
            }
        }.toTypedArray()
    }

    private fun getRenditions(): Array<Rendition> {
        val group =
            player.currentTracks.groups.firstOrNull { it.isSelected && it.type == C.TRACK_TYPE_VIDEO }
                ?: return emptyArray()

        val currentIndex = when {
            isAutoQuality -> {
                if (player.videoSize.width > 0 && player.videoSize.height > 0) {
                    (0 until group.length).firstOrNull { i ->
                        val format = group.getTrackFormat(i)
                        format.width == player.videoSize.width && format.height == player.videoSize.height
                    }
                } else null
            }
            else -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }
        }

        val result = ArrayList<Rendition>()
        for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            result.add(
                Rendition(
                    id = i.toString(),
                    width = format.width.toDouble().coerceAtLeast(0.0),
                    height = format.height.toDouble().coerceAtLeast(0.0),
                    bitrate = format.bitrate.toDouble().coerceAtLeast(0.0),
                    selected = i == currentIndex
                )
            )
        }
        return result.toTypedArray()
    }

    override fun selectRendition(rendition: Rendition?) {
        runOnMainThreadSync {
            if (rendition == null) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                    .build()
                return@runOnMainThreadSync
            }

            val group =
                player.currentTracks.groups.find { it.isSelected && it.type == C.TRACK_TYPE_VIDEO }
                    ?: return@runOnMainThreadSync

            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, rendition.id.toInt()))
                .build()
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
            setSmallIcon(applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_media_play)
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
