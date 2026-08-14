package dev.zoriya.omni

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri

class OmniCastTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = getSharedPreferences(OmniPlayer.OMNI_PREFS, Context.MODE_PRIVATE)
            .getString(OmniPlayer.NOTIFICATION_URL_PREF, null)
        val intent = url
            ?.let { Intent(Intent.ACTION_VIEW, it.toUri()).setPackage(packageName) }
            ?: packageManager.getLaunchIntentForPackage(packageName)

        try {
            startActivity(
                intent?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        } catch (e: Throwable) {
            android.util.Log.w("OmniPlayer", "could not open $url", e)
        }
        finish()
    }
}
