package app.obsilearn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.ai.Ai
import app.obsi.core.data.Omni

/** One quick button at the top of a lesson's menu. [key] is what the customise page stores. */
internal class QuickAction(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/** The most quick buttons that fit in one row. */
internal const val MAX_QUICK = 4

private enum class Page(val title: String) { Main(""), Transcript("Transcript"), Offline("Offline"), Files("Files"), Customise("Quick buttons") }

/**
 * A lesson's ⋮ menu. The top has a few quick buttons you pick yourself. Under them, one row per group;
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
    var page by remember { mutableStateOf(Page.Main) }
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

    if (page != Page.Main) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page = Page.Main }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(page.title, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(Modifier.padding(bottom = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.4f))
    }

    when (page) {
        Page.Main -> {
            val shown = learn.quick.mapNotNull { k -> quick.firstOrNull { it.key == k } }.take(MAX_QUICK)
            if (shown.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { q -> Tile(q.label, q.icon, q.on, q.enabled) { q.run() } }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.4f))
            }
            DropdownMenuItem(
                text = { Text("Mark as done") },
                leadingIcon = { Icon(Icons.Rounded.TaskAlt, null) },
                trailingIcon = { if (done) Icon(Icons.Rounded.Check, "On", tint = scheme.primary) },
                onClick = { app.setDone(videoId, !done) },
            )
            if (lesson?.media?.isNotBlank() == true) {
                Group(
                    "Transcript", Icons.Rounded.Subtitles,
                    when {
                        job != null -> if (job < 0) "Waiting its turn" else "Working, ${(job * 100).toInt()}%"
                        failed[videoId] != null -> "Last try did not work"
                        hasTranscript -> "Ready"
                        else -> "None yet"
                    },
                ) { page = Page.Transcript }
                Group(
                    "Offline", Icons.Rounded.Download,
                    when {
                        saveAt != null -> if (saveAt < 0) "Waiting to save" else "Saving, ${(saveAt * 100).toInt()}%"
                        offMode == "audio" -> "Sound saved on the phone"
                        offMode != null -> "Saved on the phone"
                        else -> "Not saved"
                    },
                ) { page = Page.Offline }
            }
            if (files.isNotEmpty()) Group("Files", Icons.Rounded.AttachFile, "${files.size} with this lesson") { page = Page.Files }
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
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant.copy(alpha = 0.4f))
            Group("Quick buttons", Icons.Rounded.Tune, "Pick the buttons at the top") { page = Page.Customise }
        }

        Page.Transcript -> {
            if (job != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
                    Text(if (job < 0) "Waiting its turn." else "Working, ${(job * 100).toInt()}%.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    if (job < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { job }, modifier = Modifier.fillMaxWidth())
                }
                DropdownMenuItem(
                    text = { Text("Stop") },
                    leadingIcon = { Icon(Icons.Rounded.Close, null) },
                    onClick = { app.transcriber.cancel(videoId) },
                )
            } else {
                failed[videoId]?.let {
                    Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).width(260.dp), style = MaterialTheme.typography.bodySmall, color = scheme.error)
                }
                if (hasTranscript && aiOn) Row2(
                    "Refine with AI", "Fixes misheard words, stops and capitals. Sends only the text.", Icons.Rounded.AutoFixHigh,
                ) { close(); app.transcriber.refine(videoId) }
                val verb = if (hasTranscript) "Redo" else "Make"
                Row2(
                    "$verb on this phone", "Free and private. Slower.", Icons.Rounded.PhoneAndroid,
                ) { close(); app.transcriber.start(listOf(videoId), redo = true, engine = "phone") }
                if (app.transcriber.hasKey()) Row2(
                    "$verb with your key", "Fast and very exact. You pay the service.", Icons.Rounded.Key,
                ) { close(); app.transcriber.start(listOf(videoId), redo = true, engine = "cloud") }
            }
        }

        Page.Offline -> {
            if (saveAt != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(260.dp)) {
                    Text(if (saveAt < 0) "Waiting to save." else "Saving, ${(saveAt * 100).toInt()}%.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    if (saveAt < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { saveAt }, modifier = Modifier.fillMaxWidth())
                }
                DropdownMenuItem(text = { Text("Stop") }, leadingIcon = { Icon(Icons.Rounded.Close, null) }, onClick = { app.offline.cancel(videoId) })
            } else {
                if (offMode != "video") Row2(
                    if (lesson?.kind == "video") "Save the video" else "Save it", "Plays with no internet.", Icons.Rounded.Movie,
                ) { close(); app.offline.saveAll(listOf(videoId), false) }
                if (offMode != "audio" && lesson?.kind == "video") Row2(
                    "Save the sound only", "Much smaller. For listening.", Icons.Rounded.Headphones,
                ) { close(); app.offline.saveAll(listOf(videoId), true) }
                if (offMode != null) Row2(
                    "Remove the copy", "Frees the space. It still plays online.", Icons.Rounded.DownloadDone,
                ) { close(); app.offline.remove(videoId) }
            }
        }

        Page.Files -> {
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
        }

        Page.Customise -> {
            val picked = learn.quick
            Text(
                "Up to $MAX_QUICK. They show in the order you tick them.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).width(260.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            quick.forEach { q ->
                val on = q.key in picked
                val full = !on && picked.count { k -> quick.any { it.key == k } } >= MAX_QUICK
                DropdownMenuItem(
                    text = { Text(q.label) },
                    leadingIcon = { Icon(q.icon, null) },
                    trailingIcon = { Checkbox(on, null, enabled = !full) },
                    enabled = !full,
                    onClick = { app.learn.update { s -> s.copy(quick = if (on) s.quick - q.key else s.quick + q.key) } },
                )
            }
        }
    }
}

/** A row that opens a group: its name, what is in it now, and a › at the end. */
@Composable
private fun Group(title: String, icon: ImageVector, now: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                Text(now, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        onClick = onClick,
    )
}

/** An action with one line under it that says what it does. */
@Composable
private fun Row2(title: String, about: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column(Modifier.width(220.dp)) {
                Text(title)
                Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
    )
}

/** A square quick button: an icon over a short name, filled while it is on. */
@Composable
private fun Tile(label: String, icon: ImageVector, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.size(width = 68.dp, height = 64.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        val tint = when {
            !enabled -> scheme.outline
            on -> scheme.onPrimaryContainer
            else -> scheme.onSurface
        }
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}
