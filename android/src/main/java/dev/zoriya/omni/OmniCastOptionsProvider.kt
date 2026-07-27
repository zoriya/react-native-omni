package dev.zoriya.omni

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class OmniCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val appId = OmniPlayer.receiverApplicationId
            ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setShowSystemOutputSwitcherOnCastIconClick(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
