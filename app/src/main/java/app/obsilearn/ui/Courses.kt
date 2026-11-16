package app.obsilearn.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsilearn.ObsiLearnApp
import app.obsi.core.obsidian.Sender
import app.obsi.core.obsidian.VaultNotes
import app.obsilearn.data.Course
import app.obsilearn.data.Lesson
import app.obsilearn.data.Module
import app.obsilearn.data.ProgressNotes
import app.obsilearn.data.SavedVideo
import app.obsi.core.data.Transcripts
import coil3.compose.AsyncImage

/** How far a course is: done lessons, all lessons, and the lesson to go on with. */
data class CourseProgress(val done: Int, val total: Int, val next: Lesson?, val nextAt: Double) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
    val percent: Int get() = (fraction * 100).toInt()
}

fun progressOf(c: Course, byId: Map<String, SavedVideo>): CourseProgress {
    val all = c.lessons
    val done = all.count { byId[it.id]?.done == true }
    // Go on with the lesson you played last, if it is not done. Else the first one not done.
    val last = all.filter { byId[it.id]?.done != true }.maxByOrNull { byId[it.id]?.playedAt ?: 0L }?.takeIf { (byId[it.id]?.playedAt ?: 0L) > 0 }
    val next = last ?: all.firstOrNull { byId[it.id]?.done != true }
    return CourseProgress(done, all.size, next, next?.let { byId[it.id]?.positionSec } ?: 0.0)
}

/** The courses on Home. */
fun LazyListScope.coursesSection(
    app: ObsiLearnApp,
    courses: List<Course>,
    byId: Map<String, SavedVideo>,
    query: String,
    onOpenCourse: (String) -> Unit,
    onPlay: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    val shown = courses.filter { c -> query.isBlank() || c.title.contains(query, true) || c.lessons.any { it.title.contains(query, true) } }
    item("courses-label") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (courses.size) { 0 -> ""; 1 -> "1 course"; else -> "${courses.size} courses" },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onManage) { Icon(Icons.Rounded.Tune, "Sources, transcripts and vault notes") }
        }
    }
    if (courses.isEmpty()) {
        item("courses-empty") { NoCourses(onAdd) }
        return
    }
    if (shown.isEmpty()) item("courses-none") { Text("No course matches \"$query\".", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(shown, key = { "course-" + it.id }) { c ->
        CourseCard(app, c, progressOf(c, byId), onOpen = { onOpenCourse(c.id) }, onPlay = onPlay)
    }
}

@Composable
private fun NoCourses(onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.School, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Add your first course", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick where your videos are: a folder on this phone, an SD card or USB stick, Google Drive, a NAS, a PC, or a link.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onAdd) { Text("Add a course") }
        }
    }
}

@Composable
private fun CourseCard(app: ObsiLearnApp, c: Course, p: CourseProgress, onOpen: () -> Unit, onPlay: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CourseCover(app, c, Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (p.total == 0) "No lessons" else "${p.done} of ${p.total} lessons · ${p.percent}%",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), drawStopIndicator = {})
                p.next?.let { n -> Text("Next: " + n.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant) }
            }
            p.next?.let { n ->
                Spacer(Modifier.width(8.dp))
                Surface(onClick = { onPlay(n.id) }, shape = CircleShape, color = scheme.primary, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, "Go on with ${n.title}", tint = scheme.onPrimary) }
                }
            }
        }
    }
}

@Composable
fun CourseCover(app: ObsiLearnApp, c: Course, modifier: Modifier) {
    val model = remember(c.cover, c.scannedAt) { app.coverModel(c) }
    Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(
            c.title.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** One course: its modules and lessons, and everything you can do with them. */
@Composable
fun CourseScreen(courseId: String, onBack: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val data by app.courses.state.collectAsState()
    val videos by app.library.videos.collectAsState()
    val saved by app.offline.saved.collectAsState()
    val saving by app.offline.progress.collectAsState()
    val writing by app.transcriber.progress.collectAsState()
    val made by app.transcriber.done.collectAsState()
    val scanning by app.courses.scanning.collectAsState()
    val course = data.courses.firstOrNull { it.id == courseId }
    if (course == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }; return }
    val byId = remember(videos) { videos.associateBy { it.meta.id } }
    val p = progressOf(course, byId)
    val hasTranscript = remember(course, made) { course.lessons.filter { app.library.loadTranscript(it.id) != null || it.subs.isNotBlank() }.map { it.id }.toSet() }
    var menu by remember { mutableStateOf(false) }
    var open by rememberSaveable(courseId) { mutableStateOf(setOf<String>()) }
    val ids = course.lessons.map { it.id }
    fun say(t: String) = android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_SHORT).show()

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(course.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow("Save all for offline", Icons.Rounded.Download) { menu = false; app.offline.saveAll(ids, audioOnly = false) }
                    MenuRow("Save all as audio only", Icons.Rounded.Headphones) { menu = false; app.offline.saveAll(ids, audioOnly = true) }
                    MenuRow("Make all transcripts", Icons.Rounded.Subtitles) { menu = false; app.transcriber.start(ids) }
                    HorizontalDivider()
                    MenuRow(if (p.done == p.total) "Mark all not done" else "Mark all done", Icons.Rounded.CheckCircle) {
                        menu = false; val all = p.done == p.total; ids.forEach { app.setDone(it, !all) }
                    }
                    MenuRow("Read the source again", Icons.Rounded.Refresh) { menu = false; app.rescan(course.sourceId) { say(it) } }
                    if (ids.any { it in saved }) MenuRow("Remove offline copies", Icons.Rounded.DownloadDone) { menu = false; ids.forEach { app.offline.remove(it) } }
                    MenuRow("Open the course note", Icons.Rounded.Description) {
                        menu = false
                        val s = app.settings.value
                        if (!VaultNotes.hasVault(s)) say("Pick your vault folder first: Settings, then Obsidian.")
                        else Sender.openNote(ctx, VaultNotes.vaultName(s), app.learn.value.notesFolder + "/" + ProgressNotes.fileName(course))
                    }
                    MenuRow("Hide this course", Icons.Rounded.VisibilityOff) { menu = false; app.courses.editCourse(course.id) { it.copy(hidden = true) }; onBack() }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            item("head") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CourseCover(app, course, Modifier.size(88.dp).clip(RoundedCornerShape(18.dp)))
                        Spacer(Modifier.width(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${p.done} of ${p.total} lessons done", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${course.modules.size} module${if (course.modules.size == 1) "" else "s"} · ${p.percent}%" +
                                    (if (saved.keys.count { it in ids } > 0) " · ${saved.keys.count { it in ids }} offline" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (course.sourceId in scanning) Text("Reading the source…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), drawStopIndicator = {})
                    p.next?.let { n ->
                        FilledTonalButton(onClick = { nav.openVideo(n.id) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                (if (p.nextAt >= 5) "Go on: " else if (p.done == 0) "Start: " else "Next: ") + n.title,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            for (m in course.modules) {
                val expanded = course.modules.size == 1 || m.id in open || (m.id !in open && "-" + m.id !in open && m.lessons.any { it.id == p.next?.id })
                item("m-" + m.id) {
                    ModuleHeader(app, m, byId, expanded, onToggle = {
                        open = if (expanded) (open - m.id) + ("-" + m.id) else (open - ("-" + m.id)) + m.id
                    })
                }
                if (expanded) {
                    items(m.lessons, key = { "l-" + it.id }) { l ->
                        LessonRow(
                            app, l, byId[l.id], saved[l.id], saving[l.id], writing[l.id], l.id in hasTranscript,
                            onOpen = { nav.openVideo(l.id) },
                        )
                    }
                    if (m.resources.isNotEmpty()) item("r-" + m.id) { Files(app, m.lessons.firstOrNull()?.id ?: course.lessons.first().id, m.resources) }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)

@Composable
private fun ModuleHeader(app: ObsiLearnApp, m: Module, byId: Map<String, SavedVideo>, expanded: Boolean, onToggle: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val ids = m.lessons.map { it.id }
    val done = ids.count { byId[it]?.done == true }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 20.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$done of ${ids.size} done", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Module options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MenuRow("Save module for offline", Icons.Rounded.Download) { menu = false; app.offline.saveAll(ids, false) }
                MenuRow("Save module as audio only", Icons.Rounded.Headphones) { menu = false; app.offline.saveAll(ids, true) }
                MenuRow("Make transcripts", Icons.Rounded.Subtitles) { menu = false; app.transcriber.start(ids) }
                MenuRow(if (done == ids.size) "Mark module not done" else "Mark module done", Icons.Rounded.CheckCircle) {
                    menu = false; val all = done == ids.size; ids.forEach { app.setDone(it, !all) }
                }
            }
        }
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Fold" else "Unfold", Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun LessonRow(
    app: ObsiLearnApp,
    l: Lesson,
    v: SavedVideo?,
    offlineMode: String?,
    saving: Float?,
    writing: Float?,
    hasTranscript: Boolean,
    onOpen: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val done = v?.done == true
    val pos = v?.positionSec ?: 0.0
    val dur = v?.meta?.durationSec ?: 0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { app.setDone(l.id, !done) }) {
            Icon(
                if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (done) "Done. Tap to mark not done." else "Not done. Tap to mark done.",
                tint = if (done) scheme.primary else scheme.outline,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(l.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val bits = buildList {
                when {
                    l.kind == "doc" -> add("Reading")
                    done -> add("Done")
                    pos >= 5 && dur > 0 -> add(Transcripts.formatTime((dur - pos).coerceAtLeast(0.0)) + " left")
                    pos >= 5 -> add("At " + Transcripts.formatTime(pos))
                    dur > 0 -> add(Transcripts.formatTime(dur.toDouble()))
                }
                if (l.kind == "audio") add("Audio")
                if (offlineMode == "video") add("Offline") else if (offlineMode == "audio") add("Offline audio")
                if (saving != null) add(if (saving < 0) "Waiting to save" else "Saving ${(saving * 100).toInt()}%")
                if (writing != null) add(if (writing < 0) "Waiting for transcript" else "Transcript ${(writing * 100).toInt()}%")
                val n = v?.highlights?.size ?: 0
                if (n > 0) add("$n highlight" + if (n == 1) "" else "s")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasTranscript) Icon(Icons.Rounded.Subtitles, "Has a transcript", Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                if (l.resources.isNotEmpty() || l.page.isNotBlank()) Icon(Icons.Rounded.AttachFile, "Has files", Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!done && pos >= 5 && dur > 0) LinearProgressIndicator(
                progress = { (pos / dur).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(0.6f).height(3.dp).clip(CircleShape), drawStopIndicator = {},
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Lesson options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (l.media.isNotBlank()) {
                    if (offlineMode != "video") MenuRow("Save for offline", Icons.Rounded.Download) { menu = false; app.offline.saveAll(listOf(l.id), false) }
                    if (offlineMode != "audio" && l.kind == "video") MenuRow("Save audio only", Icons.Rounded.Headphones) { menu = false; app.offline.saveAll(listOf(l.id), true) }
                    if (offlineMode != null) MenuRow("Remove offline copy", Icons.Rounded.DownloadDone) { menu = false; app.offline.remove(l.id) }
                    if (saving != null) MenuRow("Stop saving", Icons.Rounded.DownloadDone) { menu = false; app.offline.cancel(l.id) }
                    MenuRow(if (hasTranscript) "Make the transcript again" else "Make a transcript", Icons.Rounded.Subtitles) {
                        menu = false; app.transcriber.start(listOf(l.id), redo = hasTranscript)
                    }
                    if (writing != null) MenuRow("Stop the transcript", Icons.Rounded.Subtitles) { menu = false; app.transcriber.cancel(l.id) }
                }
                if (l.page.isNotBlank()) MenuRow("Open the lesson page", Icons.Rounded.Description) { menu = false; openFile(ctx, app, l.id, l.page, l.title, "html") }
                l.resources.forEach { r -> MenuRow(r.title + " (" + r.ext.uppercase() + ")", Icons.Rounded.AttachFile) { menu = false; openFile(ctx, app, l.id, r.uri, r.title, r.ext) } }
            }
        }
    }
}

@Composable
private fun Files(app: ObsiLearnApp, lessonId: String, files: List<app.obsilearn.data.Resource>) {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        files.forEach { r ->
            Surface(onClick = { openFile(ctx, app, lessonId, r.uri, r.title, r.ext) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(r.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.ext.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
