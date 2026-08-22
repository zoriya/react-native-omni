package dev.zoriya.omni

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class OmniCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val appId = OmniPlayer.receiverApplicationId
            ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

        val mediaOptions = CastMediaOptions.Builder()
            .setMediaSessionEnabled(false)
            .setNotificationOptions(NotificationOptions.Builder().build())
            .build()

        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setShowSystemOutputSwitcherOnCastIconClick(true)
            .setCastMediaOptions(mediaOptions)
            .setEnableReconnectionService(true)
            .setResumeSavedSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
