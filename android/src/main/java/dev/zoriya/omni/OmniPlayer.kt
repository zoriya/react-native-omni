package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import com.margelo.nitro.NitroModules
import com.margelo.nitro.omni.AndroidBackend
import com.margelo.nitro.omni.CastStatus
import com.margelo.nitro.omni.HybridOmniPlayerSpec
import com.margelo.nitro.omni.Metadata
import com.margelo.nitro.omni.MixAudioMode
import com.margelo.nitro.omni.PlayerStatus
import com.margelo.nitro.omni.Rendition
import com.margelo.nitro.omni.Source
import com.margelo.nitro.omni.Track
import com.margelo.nitro.omni.VideoSrc
import androidx.core.net.toUri
import androidx.media3.common.MediaItem.RequestMetadata
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import com.google.android.gms.cast.MediaStatus
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
) : HybridOmniPlayerSpec(), TrackProvider {
    private val ctx = NitroModules.applicationContext ?: throw Error("No Context available!")

    // exoplayer only, used to specify request headers.
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null

    val localPlayer: Player = runOnMainThreadSync {
        when (backend) {
            AndroidBackend.EXOPLAYER -> {
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                httpDataSourceFactory = http
                // karaoke subs make exoplayer crash without this...
                val renderersFactory = object : DefaultRenderersFactory(ctx) {
                    override fun buildTextRenderers(
                        context: Context,
                        output: TextOutput,
                        outputLooper: Looper,
                        extensionRendererMode: Int,
                        out: ArrayList<Renderer>,
                    ) {
                        out.add(
                            TextRenderer(output, outputLooper).apply {
                                experimentalSetLegacyDecodingEnabled(true)
                            }
                        )
                    }
                }
                ExoPlayer.Builder(ctx, renderersFactory)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http))
                            .experimentalParseSubtitlesDuringExtraction(false)
                    )
                    .build()
            }

            AndroidBackend.VLC -> VlcPlayer(ctx)
        }
    }
    override val eventMap = EventMap(this)

    private var castContext: CastContext? = null
    private val castStateListener = CastStateListener {
        eventMap.emitCastStatus(computeCastStatus())
        listenToReceiver()
        // we were only kept alive to hold this cast, it is over now
        if (abandoned && !isCasting) release()
    }

    // receivers have no queue to skip in (a queue would make them play things on
    // their own), so they ask us to do it when something else - google home, the
    // assistant, ... - requests a prev/next. same path as our own buttons.
    private fun listenToReceiver() {
        val session = castContext?.sessionManager?.currentCastSession
        eventMap.remote = session?.remoteMediaClient
        if (session == null) return
        try {
            session.setMessageReceivedCallbacks(OMNI_NAMESPACE) { _, _, message ->
                when (JSONObject(message).optString("action")) {
                    "prev" -> runOnMainThread { eventMap.emitPrev() }
                    "next" -> runOnMainThread { eventMap.emitNext() }
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("OmniPlayer", "could not listen to the receiver", e)
        }
    }

    val player: Player = runOnMainThreadSync {
        castOptions?.receiverApplicationId?.let { receiverApplicationId = it }
        notificationUrl = castOptions?.notificationUrl
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
        NavigationPlayer(
            active,
            hasPrev = { source?.metadata?.hasPrev == true },
            hasNext = { source?.metadata?.hasNext == true },
            onPrev = { eventMap.emitPrev() },
            onNext = { eventMap.emitNext() },
        )
    }

    override var showNotification: Boolean? = false
        set(value) {
            field = value
            syncNotificationService()
        }

    private var serviceRunning = false

    private fun syncNotificationService() {
        if (abandoned) return
        val shouldShow = showNotification == true && source != null
        when {
            shouldShow && !serviceRunning -> {
                val otherIsPlaying = notificationPlayer?.let { other ->
                    // an abandoned one only holds it until the cast it kept alive ends
                    other !== this && !other.abandoned &&
                        runOnMainThreadSync { other.player.isPlaying }
                } == true
                if (otherIsPlaying) {
                    throw Error("Two players can't display notifications at the same time.")
                }
                notificationPlayer = this
                ctx.startForegroundService(Intent(ctx, OmniPlayerService::class.java))
                serviceRunning = true
            }

            !shouldShow && serviceRunning -> {
                ctx.stopService(Intent(ctx, OmniPlayerService::class.java))
                if (notificationPlayer === this) notificationPlayer = null
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

    // the app let go of us. while casting we stay alive anyway: OmniPlayerService holds the
    // process (and thus the session) up, and its notification is the only remote left - we
    // keep owning both until castStateListener sees the cast end.
    @Volatile
    private var abandoned = false

    override fun abandon() {
        if (abandoned) return
        abandoned = true
        if (!runOnMainThreadSync { isCasting }) release()
    }

    // drop everything. this ends the cast session, so it only runs once there is no cast
    // left to hold (either there was none, or it just ended).
    private fun release() {
        runOnMainThread {
            castContext?.removeCastStateListener(castStateListener)
            if (notificationPlayer === this) {
                // syncNotificationService ignores us now, hand the service back ourselves
                ctx.stopService(Intent(ctx, OmniPlayerService::class.java))
                notificationPlayer = null
                serviceRunning = false
            }
            eventMap.dispose()
            player.release()
        }
    }

    override fun dispose() {
        abandon()
        super.dispose()
    }

    private fun inferSubtitleMimeType(link: String): String? {
        val ext = link.toUri().lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return when (ext) {
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            "sup" -> MimeTypes.APPLICATION_PGS
            "sub" -> MimeTypes.APPLICATION_VOBSUB
            else -> null
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
                    // exoplayer requires a mime type to pick a subtitle parser.
                    // fall back to inferring it from the link's extension so
                    // consumers don't have to provide one.
                    .setMimeType(subtitle.mimeType ?: inferSubtitleMimeType(subtitle.link))
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

    private fun sourceOf(item: MediaItem): Source {
        val meta = item.mediaMetadata
        val status = castContext?.sessionManager?.currentCastSession
            ?.remoteMediaClient?.mediaStatus
        return Source(
            src = VideoSrc(
                uri = item.localConfiguration?.uri?.toString() ?: item.mediaId,
                mimeType = item.localConfiguration?.mimeType,
                headers = emptyMap(),
            ),
            startTime = null,
            subtitles = emptyArray(),
            fonts = null,
            metadata = Metadata(
                title = meta.title?.toString() ?: "",
                album = meta.albumTitle?.toString(),
                artist = meta.artist?.toString(),
                imageLink = meta.artworkUri?.toString(),
                hasPrev = status?.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_PREVIOUS),
                hasNext = status?.isMediaCommandSupported(MediaStatus.COMMAND_QUEUE_NEXT),
            ),
            mixAudio = null,
            castId = item.mediaId,
            castData = null,
        )
    }

    fun setVideoView(surfaceView: android.view.SurfaceView) {
        runOnMainThread {
            localPlayer.setVideoSurfaceView(surfaceView)
        }
    }

    fun clearVideoView(surfaceView: android.view.SurfaceView) {
        runOnMainThread {
            localPlayer.clearVideoSurfaceView(surfaceView)
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

    override val hasPrev: Boolean get() = source?.metadata?.hasPrev == true
    override val hasNext: Boolean get() = source?.metadata?.hasNext == true
    override val status by mainThreadProperty {
        when (player.playbackState) {
            Player.STATE_IDLE,
            Player.STATE_ENDED -> PlayerStatus.IDLE

            Player.STATE_BUFFERING -> PlayerStatus.LOADING
            else -> PlayerStatus.READYTOPLAY
        }
    }

    override val isPlaying by mainThreadProperty { player.playWhenReady }
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
        set = { value -> player.setPlaybackSpeed(value.toFloat().coerceAtLeast(0.01f)) }
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
    override val renditions by mainThreadProperty {
        val group =
            player.currentTracks.groups.firstOrNull { it.isSelected && it.type == C.TRACK_TYPE_VIDEO }
                ?: return@mainThreadProperty emptyArray<Rendition>()

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
        return@mainThreadProperty result.toTypedArray()
    }

    override var isAutoQuality by mainThreadProperty {
        player.trackSelectionParameters.overrides.none {
            it.key.type == C.TRACK_TYPE_VIDEO
        }
    }


    init {
        runOnMainThreadSync {
            eventMap.player = player
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) = followMedia(item)
            })
            // check for cast rejoin
            followMedia(player.currentMediaItem)
        }
    }

    private fun followMedia(item: MediaItem?) {
        // our media only leaves the player while it is being handed over to the
        // receiver (starting a cast), it is not gone.
        if (item == null && ownItem != null) return
        if (item != null && item == ownItem) return
        val source = item?.let { sourceOf(it) }
        if (source == _source) return
        _source = source
        eventMap.emitSource(source)
        syncNotificationService()
    }

    private var _source: Source? = null
    private var ownItem: MediaItem? = null


    override var source: Source?
        get() = _source
        set(value) {
            _source = value
            eventMap.emitSource(value)
            if (value == null) {
                ownItem = null
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
            ownItem = currentItem

            runOnMainThreadSync {
                val startPositionMs = ((value.startTime ?: 0.0).coerceAtLeast(0.0)) * 1000.0
                player.setMediaItems(listOf(currentItem), 0, startPositionMs.toLong())
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
        runOnMainThreadSync { eventMap.emitPrev() }
    }

    override fun playNext() {
        runOnMainThreadSync { eventMap.emitNext() }
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
                }
                // also set preferred flags for cast selection
                val labels = track.label?.let { arrayOf(it) } ?: emptyArray()
                if (type == C.TRACK_TYPE_AUDIO) {
                    params.setPreferredAudioLanguage(track.language)
                        .setPreferredAudioLabels(*labels)
                } else {
                    params.setPreferredTextLanguage(track.language)
                        .setPreferredTextLabels(*labels)
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

        // external (sideloaded) subs aren't in the container, so their index is -1
        val sideloadedIds = source?.subtitles?.map { it.id }?.toHashSet().orEmpty()
        var index = 0

        return groups.map { group ->
            val format = group.getTrackFormat(0)
            val id = group.mediaTrackGroup.id
            val castTrackId = id.substringAfterLast("track=", "").toLongOrNull()
            val cast = castTracks[castTrackId]
            val sideloaded = trackType == C.TRACK_TYPE_TEXT && id in sideloadedIds
            Track(
                id = id,
                index = if (sideloaded) -1.0 else (index++).toDouble(),
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
        var notificationPlayer: OmniPlayer? = null

        // MediaItem RequestMetadata extras keys carrying cast-only data.
        const val CAST_ID_EXTRA = "dev.zoriya.omni.castId"
        const val CAST_DATA_EXTRA = "dev.zoriya.omni.castData"

        // channel a receiver uses to ask us to play the prev/next media.
        // it must declare it too (`customNamespaces` in the caf options).
        const val OMNI_NAMESPACE = "urn:x-cast:dev.zoriya.omni"

        // Receiver application id passed at runtime via OmniProvider's `cast`
        // prop; read by OmniCastOptionsProvider when the Cast SDK initializes.
        var receiverApplicationId: String? = null

        // `cast.notificationUrl`, opened when our notification is tapped.
        var notificationUrl: String? = null
    }
}

@SuppressLint("UnsafeOptInUsageError")
class OmniPlayerService : MediaSessionService() {
    lateinit var player: Player
    lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        val available = OmniPlayer.notificationPlayer?.player
        if (available == null) {
            startForeground(1, createImmediateNotification())
            stopSelf()
            return
        }
        player = available
        val sessionActivity = openIntent()
        mediaSession = MediaSession.Builder(this, player)
            .apply {
                sessionActivity?.let { setSessionActivity(it) }
            }
            .build()

        addSession(mediaSession)
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)

        val notification = createImmediateNotification()
        startForeground(1, notification)
        triggerNotificationUpdate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val current = OmniPlayer.notificationPlayer?.player
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

        return NotificationCompat.Builder(this, "omni_playback")
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle("Omni Player")
            .setContentText("Preparing playback...")
            .setContentIntent(openIntent())
            .setOngoing(true)
            .build()
    }

    private fun openIntent(): PendingIntent? {
        val intent = OmniPlayer.notificationUrl
            ?.let { Intent(Intent.ACTION_VIEW, it.toUri()).setPackage(packageName) }
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val casting = try {
            CastContext.getSharedInstance(this)
                .sessionManager.currentCastSession?.isConnected == true
        } catch (_: Throwable) {
            false
        }
        // keep service open if casting
        if (!casting) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        if (::mediaSession.isInitialized) mediaSession.release()
        super.onDestroy()
    }
}
