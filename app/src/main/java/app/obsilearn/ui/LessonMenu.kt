package app.obsilearn.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.ai.Ai
import app.obsi.core.data.Omni
import app.obsi.core.ui.ActionMenu
import app.obsi.core.ui.MenuActionRow
import app.obsi.core.ui.MenuGroupRow
import app.obsi.core.ui.MenuPage
import app.obsi.core.ui.QuickAction

/**
 * A lesson's ⋮ menu, on the core's [ActionMenu]: quick buttons you pick, then one row per group;
 * a row with › opens that group in the same menu, with a back arrow, so the first page stays short.
 */
@Composable
internal fun ColumnScope.LessonMenuItems(
    videoId: String,
    done: Boolean,
    hasTranscript: Boolean,
    quick: List<QuickAction>,
    close: () -> Unit,
    onAbout: () -> Unit,
    onActions: () -> Unit,
    openFile: (uri: String, title: String, ext: String) -> Unit,
) {
    val app = LocalApp.current
    val scheme = MaterialTheme.colorScheme
    val learn by app.learn.state.collectAsState()
    val lesson = remember(videoId) { app.courses.find(videoId)?.third }
    val working by app.transcriber.progress.collectAsState()
    val failed by app.transcriber.failed.collectAsState()
    val saved by app.offline.saved.collectAsState()
    val saving by app.offline.progress.collectAsState()
    val aiOn by Ai.usable.collectAsState()
    val job = working[videoId]
    val offMode = saved[videoId]
    val saveAt = saving[videoId]
    val files = remember(lesson) {
        buildList {
            if (lesson != null && lesson.page.isNotBlank()) add(Triple(lesson.page, "Lesson page", "html"))
            lesson?.resources?.forEach { add(Triple(it.uri, it.title, it.ext)) }
        }
    }

    val pages = listOf(
        MenuPage("transcript", "Transcript") {
            if (job != null) {
                Working(if (job < 0) "Waiting its turn." else "Working, ${(job * 100).toInt()}%.", job)
                DropdownMenuItem(text = { Text("Stop") }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { app.transcriber.cancel(videoId) })
            } else {
                failed[videoId]?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(260.dp), style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
                if (hasTranscript && aiOn) MenuActionRow(
                    "Refine with AI", "Fixes misheard words, stops and capitals. Sends only the text.", Icons.Rounded.AutoFixHigh,
                ) { close(); app.transcriber.refine(videoId) }
                val verb = if (hasTranscript) "Redo" else "Make"
                MenuActionRow("$verb on this phone", "Free and private. Slower.", Icons.Rounded.PhoneAndroid) {
                    close(); app.transcriber.start(listOf(videoId), redo = true, engine = "phone")
                }
                if (app.transcriber.hasKey()) MenuActionRow("$verb with your key", "Fast and very exact. You pay the service.", Icons.Rounded.Key) {
                    close(); app.transcriber.start(listOf(videoId), redo = true, engine = "cloud")
                }
            }
        },
        MenuPage("offline", "Offline") {
            if (saveAt != null) {
                Working(if (saveAt < 0) "Waiting to save." else "Saving, ${(saveAt * 100).toInt()}%.", saveAt)
                DropdownMenuItem(text = { Text("Stop") }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { app.offline.cancel(videoId) })
            } else {
                if (offMode != "video") MenuActionRow(
                    if (lesson?.kind == "video") "Save the video" else "Save it", "Plays with no internet.", Icons.Rounded.Movie,
                ) { close(); app.offline.saveAll(listOf(videoId), false) }
                if (offMode != "audio" && lesson?.kind == "video") MenuActionRow(
                    "Save the sound only", "Much smaller. For listening.", Icons.Rounded.Headphones,
                ) { close(); app.offline.saveAll(listOf(videoId), true) }
                if (offMode != null) MenuActionRow(
                    "Remove the copy", "Frees the space. It still plays online.", Icons.Rounded.DownloadDone,
                ) { close(); app.offline.remove(videoId) }
            }
        },
        MenuPage("files", "Files") {
            var q by remember { mutableStateOf("") }
            // A long list gets a search box, like every picker in the Obsi apps.
            if (files.size > 6) OutlinedTextField(
                q, { q = it }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(280.dp),
                placeholder = { Text("Type to find") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
            )
            val list = if (q.isBlank()) files else files
                .mapNotNull { f -> Omni.fuzzy(f.second, q.trim())?.let { f to it.score } }
                .sortedByDescending { it.second }.map { it.first }
            list.forEach { (uri, title, ext) ->
                DropdownMenuItem(
                    text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(if (ext == "html") Icons.Rounded.Description else Icons.Rounded.AttachFile, null) },
                    trailingIcon = { Text(if (ext == "html") "" else ext.uppercase(), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) },
                    onClick = { close(); openFile(uri, title, ext) },
                )
            }
            if (list.isEmpty()) Text("No file has that name.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        },
    )

    ActionMenu(quick, learn.quick, onPicked = { keys -> app.learn.update { it.copy(quick = keys) } }, pages = pages) { open ->
        DropdownMenuItem(
            text = { Text("Mark as done") },
            leadingIcon = { Icon(Icons.Rounded.TaskAlt, null) },
            trailingIcon = { if (done) Icon(Icons.Rounded.Check, "On", tint = scheme.primary) },
            onClick = { app.setDone(videoId, !done) },
        )
        if (lesson?.media?.isNotBlank() == true) {
            MenuGroupRow(
                "Transcript", Icons.Rounded.Subtitles,
                when {
                    job != null -> if (job < 0) "Waiting its turn" else "Working, ${(job * 100).toInt()}%"
                    failed[videoId] != null -> "Last try did not work"
                    hasTranscript -> "Ready"
                    else -> "None yet"
                },
            ) { open("transcript") }
            MenuGroupRow(
                "Offline", Icons.Rounded.Download,
                when {
                    saveAt != null -> if (saveAt < 0) "Waiting to save" else "Saving, ${(saveAt * 100).toInt()}%"
                    offMode == "audio" -> "Sound saved on the phone"
                    offMode != null -> "Saved on the phone"
                    else -> "Not saved"
                },
            ) { open("offline") }
        }
        if (files.isNotEmpty()) MenuGroupRow("Files", Icons.Rounded.AttachFile, "${files.size} with this lesson") { open("files") }
        DropdownMenuItem(
            text = { Text("Tags, queue and note") },
            leadingIcon = { Icon(Icons.Rounded.Sell, null) },
            onClick = { close(); onActions() },
        )
        DropdownMenuItem(
            text = { Text("About this lesson") },
            leadingIcon = { Icon(Icons.Rounded.Description, null) },
            onClick = { close(); onAbout() },
        )
    }
}

/** A line saying how far a job is, with its bar. [at] below 0 is a bar that only moves. */
@Composable
private fun Working(text: String, at: Float) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        if (at < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { at }, modifier = Modifier.fillMaxWidth())
    }
}
