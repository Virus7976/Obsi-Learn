package app.obsilearn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsilearn.data.Course
import app.obsilearn.data.SavedVideo

/** One file of a lesson, as the Files tab lists it. */
class CourseFile(val course: Course, val lessonId: String, val lessonTitle: String, val title: String, val uri: String, val ext: String)

/** The kinds the Files tab can show alone. */
val FileKinds = listOf("all" to "All", "pdf" to "PDFs", "doc" to "Documents", "slides" to "Slides", "sheet" to "Sheets", "page" to "Pages", "other" to "Other")

fun kindOf(ext: String): String = when (ext.lowercase()) {
    "pdf" -> "pdf"
    "doc", "docx", "odt", "rtf", "txt", "md", "epub", "pages" -> "doc"
    "ppt", "pptx", "key", "odp" -> "slides"
    "xls", "xlsx", "csv", "tsv", "ods", "numbers" -> "sheet"
    "html", "htm" -> "page"
    else -> "other"
}

/** Every file in every course, in course order. */
fun courseFiles(courses: List<Course>): List<CourseFile> = courses.flatMap { c ->
    c.lessons.flatMap { l ->
        (if (l.page.isNotBlank()) listOf(CourseFile(c, l.id, l.title, if (l.media.isBlank()) l.title else "Lesson page", l.page, "html")) else emptyList()) +
            l.resources.map { r -> CourseFile(c, l.id, l.title, r.title, r.uri, r.ext) }
    }
}

/**
 * The Files tab: every PDF, worksheet, deck and page of your courses in one place, grouped by course.
 * Each row says how far you read and how many highlights it has. Tap to read it in the app.
 */
fun LazyListScope.filesTab(
    files: List<CourseFile>,
    items: Map<String, SavedVideo>,
    fileId: (String, String) -> String,
    offline: Set<String>,
    kind: String,
    onKind: (String) -> Unit,
    onOpen: (CourseFile) -> Unit,
) {
    val present = files.map { kindOf(it.ext) }.toSet()
    val shown = if (kind == "all") files else files.filter { kindOf(it.ext) == kind }
    if (files.isEmpty()) {
        item("files-empty") {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No files yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "PDFs, worksheets, slides and pages from your courses show up here, all in one place.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    if (present.size > 1) item("files-kinds") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            items(FileKinds.filter { it.first == "all" || it.first in present }, key = { it.first }) { (id, label) ->
                FilterChip(selected = kind == id, onClick = { onKind(id) }, label = { Text(label) })
            }
        }
    }
    shown.groupBy { it.course.id }.forEach { (cid, group) ->
        item("files-h-$cid") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group.first().course.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${group.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(group, key = { "files-" + it.lessonId + it.uri }) { f -> FileRow(f, items[fileId(f.lessonId, f.uri)], f.lessonId in offline) { onOpen(f) } }
    }
}

@Composable
private fun FileRow(f: CourseFile, item: SavedVideo?, saved: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val words = item?.meta?.words ?: 0
    val frac = if (words > 0) (item!!.positionSec / words).toFloat().coerceIn(0f, 1f) else 0f
    val marks = item?.highlights?.size ?: 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(fileIcon(f.ext), null, tint = scheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val bits = buildList {
                if (f.title != f.lessonTitle) add(f.lessonTitle)
                add(f.ext.uppercase())
                if (frac > 0.01f) add("${(frac * 100).toInt()}% read")
            }
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (frac > 0.01f) LinearProgressIndicator(progress = { frac }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.5f).height(3.dp).clip(RoundedCornerShape(2.dp)), drawStopIndicator = {})
        }
        if (marks > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Icon(Icons.Rounded.BorderColor, null, Modifier.size(14.dp), tint = scheme.tertiary)
            Spacer(Modifier.width(3.dp))
            Text("$marks", style = MaterialTheme.typography.labelMedium, color = scheme.tertiary)
        }
        if (saved) Icon(Icons.Rounded.DownloadDone, "Saved for offline", Modifier.padding(start = 8.dp).size(16.dp), tint = scheme.onSurfaceVariant)
    }
}
