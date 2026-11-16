package app.obsilearn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsilearn.data.Course
import app.obsilearn.data.Source
import kotlinx.coroutines.flow.MutableStateFlow

/** Any screen can ask for the setup of a source. */
object SetupNav {
    val requests = MutableStateFlow<String?>(null)
    fun open(sourceId: String) { requests.value = sourceId }
}

/**
 * After a source is added: what ObsiLearn found, and how you want it shown.
 * Every choice shows its result at once, from the files it already read.
 */
@Composable
fun SetupScreen(sourceId: String, onDone: () -> Unit) {
    val app = LocalApp.current
    val scheme = MaterialTheme.colorScheme
    val data by app.courses.state.collectAsState()
    val saved = data.sources.firstOrNull { it.id == sourceId }
    if (saved == null) { LaunchedEffect(Unit) { onDone() }; return }
    var draft by remember(sourceId) { mutableStateOf(saved) }
    var ready by remember { mutableStateOf(app.courses.preview(saved) != null) }
    // The files are not in memory (an older source, or the app was closed): read them once.
    LaunchedEffect(sourceId) { if (!ready) { runCatching { app.courses.scan(sourceId) }; ready = true } }
    val preview = remember(draft, ready) { app.courses.preview(draft) ?: data.courses.filter { it.sourceId == sourceId } }
    val depth = remember(ready) { app.courses.depth(sourceId) }

    fun save() {
        app.courses.editSource(sourceId) { draft.copy(setUp = true) }
        app.rescan(sourceId)
        onDone()
    }

    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Done") }
            Text("Set up " + saved.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!ready) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Spacer(Modifier.size(12.dp))
                Text("Reading the folders…")
            }
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Found(preview)

            if (depth >= 2) Question("What is in this folder?") {
                Choice("One course", "The whole folder is one course.", draft.oneCourse) { draft = draft.copy(oneCourse = true) }
                Choice("Many courses", "Each folder at the top is its own course.", !draft.oneCourse) { draft = draft.copy(oneCourse = false) }
            }

            if (depth >= (if (draft.oneCourse) 1 else 2)) Question("Modules") {
                Choice("Every folder", "A folder in a folder is a module too, named Parent / Child.", draft.modules == "folders") { draft = draft.copy(modules = "folders") }
                if (depth >= (if (draft.oneCourse) 2 else 3)) Choice("Top folders only", "Folders inside a module join that module.", draft.modules == "top") { draft = draft.copy(modules = "top") }
                Choice("No modules", "All lessons in one list.", draft.modules == "none") { draft = draft.copy(modules = "none") }
            }

            Question("PDFs, slides and pages") {
                Choice("Part of the lesson", "A file with the same number as a video opens from that video.", !draft.docsAsLessons) { draft = draft.copy(docsAsLessons = false) }
                Choice("Lessons of their own", "Each file is a reading lesson you can mark as done.", draft.docsAsLessons) { draft = draft.copy(docsAsLessons = true) }
            }

            if (preview.size == 1) {
                val c = preview.single()
                OutlinedTextField(
                    draft.titles[c.id] ?: c.title,
                    { v -> draft = draft.copy(titles = draft.titles + (c.id to v)) },
                    label = { Text("Course name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("How it will look", style = MaterialTheme.typography.titleSmall)
            preview.take(6).forEach { CoursePreview(it) }
            if (preview.size > 6) Text("And ${preview.size - 6} more courses.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun Found(courses: List<Course>) {
    val lessons = courses.flatMap { it.lessons }
    val videos = lessons.count { it.media.isNotBlank() }
    val reading = lessons.size - videos
    val subs = lessons.count { it.subs.isNotBlank() }
    val files = lessons.sumOf { it.resources.size } + courses.sumOf { c -> c.modules.sumOf { it.resources.size } }
    val bits = buildList {
        add(if (courses.size == 1) "1 course" else "${courses.size} courses")
        add("${courses.sumOf { it.modules.size }} modules")
        add("$videos videos")
        if (reading > 0) add("$reading reading lessons")
        if (files > 0) add("$files files")
        if (subs > 0) add("$subs transcripts")
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ObsiLearn found", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun Question(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun Choice(title: String, hint: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, shape = RoundedCornerShape(14.dp),
        color = if (on) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surfaceContainer,
        border = if (on) BorderStroke(1.dp, scheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = on, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CoursePreview(c: Course) {
    val scheme = MaterialTheme.colorScheme
    var open by remember(c.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable { open = !open }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val shown = if (open) c.modules else c.modules.take(4)
            shown.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(m.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${m.lessons.size}", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                if (open) m.lessons.take(8).forEach { l ->
                    Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (l.media.isNotBlank()) Icons.Rounded.PlayCircle else Icons.Rounded.Article, null, Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(l.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!open && c.modules.size > 4) Text("And ${c.modules.size - 4} more. Tap to see all.", style = MaterialTheme.typography.bodySmall, color = scheme.primary)
            else if (!open) Text("Tap to see the lessons.", style = MaterialTheme.typography.bodySmall, color = scheme.primary)
        }
    }
}
