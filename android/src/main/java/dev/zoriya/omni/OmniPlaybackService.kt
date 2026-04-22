package dev.zoriya.omni

import android.annotation.SuppressLint
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.margelo.nitro.NitroModules
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("UnsafeOptInUsageError")
class OmniPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        serviceRef.set(this)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .build()
        )
        attachedPlayer.get()?.let { ensureSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        attachedPlayer.get()?.let { ensureSession(it) }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        serviceRef.compareAndSet(this, null)
        super.onDestroy()
    }

    private fun ensureSession(player: Player) {
        if (mediaSession?.player === player) return
        mediaSession?.release()
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .build()
    }

    private fun clearSessionIf(player: Player) {
        if (mediaSession?.player !== player) return
        mediaSession?.release()
        mediaSession = null
        stopSelf()
    }

    companion object {
        private const val SESSION_ID = "omni-session"
        private const val NOTIFICATION_CHANNEL_ID = "omni_playback"
        private val attachedPlayer = AtomicReference<Player?>(null)
        private val serviceRef = AtomicReference<OmniPlaybackService?>(null)

        fun attachPlayer(player: Player) {
            attachedPlayer.set(player)
            serviceRef.get()?.ensureSession(player)
        }

        fun detachPlayer(player: Player) {
            if (!attachedPlayer.compareAndSet(player, null)) return
            serviceRef.get()?.clearSessionIf(player)
        }

        fun ensureStarted(player: Player) {
            val context = NitroModules.Companion.applicationContext ?: return
            attachPlayer(player)
            ContextCompat.startForegroundService(
                context,
                Intent(context, OmniPlaybackService::class.java)
            )
        }
    }
}