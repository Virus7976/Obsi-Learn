package app.obsilearn

import app.obsi.core.*
import app.obsi.core.data.*
import app.obsi.core.obsidian.*
import app.obsi.core.ui.*
import app.obsi.core.update.*
import app.obsi.core.link.*
import app.obsi.core.share.*

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.obsilearn.ui.AppRoot

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<String?>(null)
    private var routedAway = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ObsiLearnApp
        app.player.connectService(this)
        // A restart (like a turn of the phone) must not send the same link again.
        if (savedInstanceState == null) incoming = linkFrom(intent)
        // Opened only to pass a link on: step out of the way.
        if (routedAway) { finish(); return }
        openFrom(intent)
        if (intent?.getBooleanExtra(ReviewReminder.EXTRA_REVIEW, false) == true) app.openReview.value = true
        if (intent?.getBooleanExtra(EXTRA_NOW_PLAYING, false) == true) app.openNowPlaying.value = true
        intent?.getStringExtra(SaveAsk.EXTRA_SAVE)?.let { app.askSave.value = it }
        setContent {
            AppRoot(app, incoming) { incoming = null }
        }
    }

    override fun onResume() {
        super.onResume()
        // The notification says this: open the app to unlock.
        (application as ObsiLearnApp).player.phoneUnlocked()
        // Another phone may have moved on in a course. Its notes in the vault say so.
        (application as ObsiLearnApp).pullProgressNotes()
    }

    override fun onStart() {
        super.onStart()
        val app = application as ObsiLearnApp
        app.inForeground = true
        // The sheet is about to show, so the notification has done its job.
        if (app.askSave.value != null) SaveAsk.clear(app)
    }

    override fun onStop() {
        super.onStop()
        (application as ObsiLearnApp).inForeground = false
        (application as ObsiLearnApp).player.savePosition()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(ReviewReminder.EXTRA_REVIEW, false)) (application as ObsiLearnApp).openReview.value = true
        if (intent.getBooleanExtra(EXTRA_NOW_PLAYING, false)) (application as ObsiLearnApp).openNowPlaying.value = true
        intent.getStringExtra(SaveAsk.EXTRA_SAVE)?.let { (application as ObsiLearnApp).askSave.value = it }
        linkFrom(intent)?.let { incoming = it }
        openFrom(intent)
    }

    companion object {
        const val EXTRA_NOW_PLAYING = "app.obsilearn.NOW_PLAYING"
    }

    /**
     * A lesson link from a note (obsilearn://lesson/<id>?t=<second>) opens that lesson there.
     * A video file or a link to one is returned, to be added as a lesson.
     */
    private fun linkFrom(intent: Intent?): String? {
        intent ?: return null
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        val uri = android.net.Uri.parse(text.trim())
        if (uri.scheme == "obsilearn") {
            val id = uri.pathSegments.lastOrNull() ?: uri.host ?: return null
            val at = uri.getQueryParameter("t")?.toDoubleOrNull() ?: -1.0
            (application as ObsiLearnApp).openItem.value = id to at
            return null
        }
        if (uri.scheme == "content") return text.trim()
        // An article, video site or Bible link goes to the app that reads it.
        if (app.obsi.core.link.Links.route(this, intent, text)) { routedAway = true; return null }
        return app.obsi.core.link.Links.firstLink(text)
    }

    /** Another Obsi app asked to open one of our videos, at a highlight. */
    private fun openFrom(intent: Intent?) {
        val id = intent?.getStringExtra(app.obsi.core.link.Links.EXTRA_OPEN_ID) ?: return
        val at = intent.getStringExtra(app.obsi.core.link.Links.EXTRA_OPEN_AT)?.toDoubleOrNull() ?: -1.0
        intent.removeExtra(app.obsi.core.link.Links.EXTRA_OPEN_ID)
        (application as ObsiLearnApp).openItem.value = id to at
    }
}
