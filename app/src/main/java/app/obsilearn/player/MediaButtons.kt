package app.obsilearn.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.media3.session.MediaButtonReceiver

/**
 * Headset and media-card buttons while the app is closed: they start the lesson player again.
 * Media3's own receiver gives up when an app has two media services, and ObsiLearn has two
 * (the lesson player and read aloud), so this one always picks the lesson player.
 */
class MediaButtons : MediaButtonReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val start = Intent(intent).setComponent(ComponentName(context, PlaybackService::class.java))
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(start) else context.startService(start)
        } catch (e: Exception) {
            // Android 12+ may refuse to start a service from the background. The button then does nothing.
            Log.w("MediaButtons", "Could not start the player", e)
        }
    }
}
