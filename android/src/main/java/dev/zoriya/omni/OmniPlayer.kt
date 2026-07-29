package dev.zoriya.omni

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.margelo.nitro.NitroModules
import com.margelo.nitro.omni.AndroidBackend
import com.margelo.nitro.omni.CastStatus
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.MixAudioMode
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Source
import com.margelo.nitro.omni.Track
import androidx.core.net.toUri
import androidx.media3.common.MediaItem.RequestMetadata
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.margelo.nitro.omni.CastOptions
import dev.zoriya.omni.utils.ThreadHelper.mainThreadProperty
import dev.zoriya.omni.utils.ThreadHelper.runOnMainThread
import dev.zoriya.omni.utils.ThreadHelper.runOnMainThreadSync
import org.json.JSONObject

@SuppressLint("UnsafeOptInUsageError")
class OmniPlayer(
    private val backend: AndroidBackend = AndroidBackend.VLC,
    castOptions: CastOptions? = null,
) : HybridOmniPlayerSpec() {
    private val ctx = NitroModules.applicationContext ?: throw Error("No Context available!")

    // exoplayer only, used to specify request headers.
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null

    val localPlayer: Player = runOnMainThreadSync {
        when (backend) {
            AndroidBackend.EXOPLAYER -> {
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                httpDataSourceFactory = http
                ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
                    )
                    .build()
            }

            AndroidBackend.VLC -> VlcPlayer(ctx)
        }
    }
    override val eventMap = EventMap()

    private var castContext: CastContext? = null
    private val castStateListener =
        CastStateListener { eventMap.emitCastStatus(computeCastStatus()) }

    val player: Player = runOnMainThreadSync {
        castOptions?.receiverApplicationId?.let { receiverApplicationId = it }
        val cc = try {
            CastContext.getSharedInstance(ctx)
        } catch (_: Throwable) {
            // No Google Play Services / Cast SDK -> casting unsupported.
            null
        }
        castContext = cc
        val active = if (cc == null) {
            localPlayer
        } else {
            cc.addCastStateListener(castStateListener)
            val remote = RemoteCastPlayer.Builder(ctx)
                .setMediaItemConverter(OmniMediaItemConverter())
                .setTrackSelector(OmniCastTrackSelector())
                .build()
            CastPlayer.Builder(ctx)
                .setLocalPlayer(localPlayer)
                .setRemotePlayer(remote)
                .build()
        }
        eventMap.player = active
        active
    }

    override var showNotification: Boolean? = false
        set(value) {
            field = value
            syncNotificationService()
        }

    private var serviceRunning = false

    private fun syncNotificationService() {
        val shouldShow = showNotification == true && source != null
        when {
            shouldShow && !serviceRunning -> {
                val otherIsPlaying = notificationPlayer?.let { other ->
                    other !== localPlayer && runOnMainThreadSync { other.isPlaying }
                } == true
                if (otherIsPlaying) {
                    throw Error("Two players can't display notifications at the same time.")
                }
                notificationPlayer = localPlayer
                ctx.startForegroundService(Intent(ctx, OmniPlayerService::class.java))
                serviceRunning = true
            }

            !shouldShow && serviceRunning -> {
                ctx.stopService(Intent(ctx, OmniPlayerService::class.java))
                if (notificationPlayer == localPlayer) notificationPlayer = null
                serviceRunning = false
            }
        }
    }

    override val castStatus: CastStatus
        get() = runOnMainThreadSync { computeCastStatus() }

    val isCasting: Boolean
        get() = castContext?.sessionManager?.currentCastSession?.isConnected == true

    private fun computeCastStatus(): CastStatus {
        val cc = castContext ?: return CastStatus.UNSUPPORTED
        return when (cc.castState) {
            CastState.NO_DEVICES_AVAILABLE -> CastStatus.UNAVAILABLE
            CastState.NOT_CONNECTED -> CastStatus.AVAILABLE
            CastState.CONNECTING -> CastStatus.CONNECTING
            CastState.CONNECTED -> CastStatus.CONNECTED
            else -> CastStatus.UNAVAILABLE
        }
    }

    override fun toggleCastStatus() {
        runOnMainThread {
            val cc = castContext ?: return@runOnMainThread
            val session = cc.sessionManager.currentCastSession
            if (session != null && session.isConnected) {
                cc.sessionManager.endCurrentSession(true)
                return@runOnMainThread
            }
            val activity = ctx.currentActivity ?: return@runOnMainThread
            if (SystemOutputSwitcherDialogController.showDialog(activity)) {
                return@runOnMainThread
            }
            val selector = cc.mergedSelector ?: return@runOnMainThread
            MediaRouteChooserDialog(activity).apply { routeSelector = selector }.show()
        }
    }

    override fun dispose() {
        showNotification = false
        super.dispose()

        eventMap.dispose()
        runOnMainThread {
            castContext?.removeCastStateListener(castStateListener)
            // release both cast and local players.
            player.release()
        }
    }

    private fun buildMediaItem(
        src: com.margelo.nitro.omni.VideoSrc,
        metadata: com.margelo.nitro.omni.Metadata?,
        subtitles: Array<com.margelo.nitro.omni.Subtitle>,
        castId: String? = null,
        castData: Map<String, String>? = null,
    ): MediaItem {
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
                    .setExtras(
                        Bundle().apply {
                            castId?.let { putString(CAST_ID_EXTRA, it) }
                            castData?.let { data ->
                                putString(CAST_DATA_EXTRA, JSONObject(data as Map<*, *>).toString())
                            }
                            // vlc only path (and it only supports a few headers :c).
                            for ((name, value) in src.headers) putString(name, value)
                        }
                    )
                    .build()
            )
            .build()
    }

    fun setVideoView(surfaceView: android.view.SurfaceView?) {
        runOnMainThread {
            if (surfaceView == null) {
                localPlayer.clearVideoSurface()
            } else {
                localPlayer.setVideoSurfaceView(surfaceView)
            }
        }
    }

    // rebuild video pipeline after pip, noop for exoplayer that recover on it's own.
    fun rebuildVideoOutput() {
        runOnMainThread {
            (localPlayer as? VlcPlayer)?.rebuildVideoOutput()
        }
    }

    fun updateVideoLayout(width: Int, height: Int) {
        runOnMainThread {
            (localPlayer as? VlcPlayer)?.updateVideoLayout(width, height)
        }
    }

    override val hasPrev: Boolean get() = player.hasPreviousMediaItem()
    override val hasNext: Boolean get() = player.hasNextMediaItem()
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

    override var muted by mainThreadProperty(
        get = { player.volume <= 0f },
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

    override var source: Source? = null
        set(value) {
            field = value
            if (value == null) {
                runOnMainThreadSync {
                    player.clearMediaItems()
                }
                syncNotificationService()
                return
            }
            val handleAudioFocus =
                (value.mixAudio ?: MixAudioMode.AUTO) != MixAudioMode.MIXWITHOTHERS
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            // audio focus only makes sense locally. not in a cast session
            runOnMainThread { localPlayer.setAudioAttributes(audioAttributes, handleAudioFocus) }

            val src = value.src
            httpDataSourceFactory?.setDefaultRequestProperties(src.headers)

            val currentItem = buildMediaItem(
                src,
                value.metadata,
                value.subtitles,
                value.castId,
                value.castData,
            )
            val mediaItems = mutableListOf<MediaItem>()
            if (value.metadata?.hasPrev == true) mediaItems.add(currentItem)
            mediaItems.add(currentItem)
            if (value.metadata?.hasNext == true) mediaItems.add(currentItem)

            runOnMainThreadSync {
                val startIndex = if (value.metadata?.hasPrev == true) 1 else 0
                val startPositionMs = ((value.startTime ?: 0.0).coerceAtLeast(0.0)) * 1000.0
                player.setMediaItems(mediaItems, startIndex, startPositionMs.toLong())
                player.prepare()
            }
            syncNotificationService()
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

    override fun selectAudio(audio: Track) = selectTrack(C.TRACK_TYPE_AUDIO, audio)

    override fun selectSubtitle(subtitle: Track?) = selectTrack(C.TRACK_TYPE_TEXT, subtitle)

    private fun selectTrack(type: Int, track: Track?) {
        runOnMainThreadSync {
            val params = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(type)
            if (track == null) {
                params.setTrackTypeDisabled(type, true)
            } else {
                params.setTrackTypeDisabled(type, false)
                val group = player.currentTracks.groups.firstOrNull {
                    it.type == type && it.mediaTrackGroup.id == track.id
                }?.mediaTrackGroup
                if (group != null) {
                    params.setOverrideForType(TrackSelectionOverride(group, 0))
                } else {
                    val labels = track.label?.let { arrayOf(it) } ?: emptyArray()
                    if (type == C.TRACK_TYPE_AUDIO) {
                        params.setPreferredAudioLanguage(track.language)
                            .setPreferredAudioLabels(*labels)
                    } else {
                        params.setPreferredTextLanguage(track.language)
                            .setPreferredTextLabels(*labels)
                    }
                }
            }
            player.trackSelectionParameters = params.build()
        }
    }

    private fun tracksByType(trackType: Int): Array<Track> {
        val groups = player.currentTracks.groups.filter { it.type == trackType }
        if (groups.isEmpty()) return emptyArray()

        // when casting tracks are stripped of some metadata, recover it
        val castTracks = castMediaTracksById()

        return groups.map { group ->
            val format = group.getTrackFormat(0)

            val castTrackId = group.mediaTrackGroup.id.substringAfterLast("track=", "").toLongOrNull()
            val cast = castTracks[castTrackId]
            Track(
                id = group.mediaTrackGroup.id,
                label = cast?.name ?: format.label,
                language = cast?.language ?: format.language,
                selected = group.isSelected
            )
        }.toTypedArray()
    }

    private fun castMediaTracksById(): Map<Long, com.google.android.gms.cast.MediaTrack> {
        if (!isCasting) return emptyMap()
        val tracks = castContext?.sessionManager?.currentCastSession
            ?.remoteMediaClient?.mediaInfo?.mediaTracks ?: return emptyMap()
        return tracks.associateBy { it.id }
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
                .setOverrideForType(
                    TrackSelectionOverride(
                        group.mediaTrackGroup,
                        rendition.id.toInt()
                    )
                )
                .build()
        }
    }

    companion object {
        var notificationPlayer: Player? = null

        // MediaItem RequestMetadata extras keys carrying cast-only data.
        const val CAST_ID_EXTRA = "dev.zoriya.omni.castId"
        const val CAST_DATA_EXTRA = "dev.zoriya.omni.castData"

        // Receiver application id passed at runtime via OmniProvider's `cast`
        // prop; read by OmniCastOptionsProvider when the Cast SDK initializes.
        var receiverApplicationId: String? = null
    }
}

@SuppressLint("UnsafeOptInUsageError")
class OmniPlayerService : MediaSessionService() {
    lateinit var player: Player
    lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        val available = OmniPlayer.notificationPlayer
        if (available == null) {
            startForeground(1, createImmediateNotification())
            stopSelf()
            return
        }
        player = available
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
            setSmallIcon(applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_media_play)
        })
        addSession(mediaSession)
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        val notification = createImmediateNotification()
        startForeground(1, notification)
        triggerNotificationUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = OmniPlayer.notificationPlayer
        if (current != null && ::mediaSession.isInitialized && mediaSession.player !== current) {
            player = current
            mediaSession.player = current
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun createImmediateNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "omni_playback",
                "Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "omni_playback")
            .setSmallIcon(applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_media_play)
            .setContentTitle("Omni Player")
            .setContentText("Preparing playback...")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        if (::mediaSession.isInitialized) mediaSession.release()
        super.onDestroy()
    }
}
