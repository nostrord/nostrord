package org.nostr.nostrord.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import org.nostr.nostrord.R

/**
 * Must be called once from Application.onCreate() before [playNotificationSound].
 */
object AndroidNotificationSoundInit {
    @SuppressLint("StaticFieldLeak") // Application context is safe to hold statically
    internal var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}

actual fun playNotificationSound() {
    val context = AndroidNotificationSoundInit.appContext ?: return
    try {
        val player = MediaPlayer.create(context, R.raw.message_incoming) ?: return
        player.setVolume(0.6f, 0.6f)
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ -> mp.release(); true }
        player.start()
    } catch (_: Throwable) {
        // Audio focus conflict or codec failure — silent fallback.
    }
}
