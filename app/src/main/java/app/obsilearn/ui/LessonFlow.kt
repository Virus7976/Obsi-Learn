package app.obsilearn.ui

import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import app.obsi.core.R as CoreR
import app.obsi.core.data.ArticleDoc
import app.obsi.core.data.Highlights
import app.obsi.core.ui.customTypeface
import app.obsilearn.data.DocText
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.widthIn
import app.obsi.core.ui.ReaderStyle
import app.obsi.core.ui.TranscriptView
import app.obsi.core.ui.rememberPictures
import app.obsilearn.data.Lesson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/** The icon for a file type, so a row of files can be told apart at a glance. */
fun fileIcon(ext: String): ImageVector = when (ext.lowercase()) {
    "pdf" -> Icons.Rounded.PictureAsPdf
    "ppt", "pptx", "key", "odp" -> Icons.Rounded.Slideshow
    "xls", "xlsx", "csv", "tsv", "ods", "numbers" -> Icons.Rounded.TableChart
    "zip" -> Icons.Rounded.FolderZip
    "png", "jpg", "jpeg", "gif", "webp", "heic" -> Icons.Rounded.Image
    "html", "htm" -> Icons.AutoMirrored.Rounded.Article
    else -> Icons.Rounded.Description
}

/**
 * The lesson's page and files, as a row of chips under the video. Each opens inside the app. A chip shows
 * a dot once the file has highlights.
 */
@Composable
fun LessonFilesRow(lessonId: String, lesson: Lesson?, modifier: Modifier = Modifier) {
    if (lesson == null || (lesson.page.isBlank() && lesson.resources.isEmpty())) return
    val app = LocalApp.current
    val ctx = LocalContext.current
    val videos by app.library.videos.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val files = buildList {
        if (lesson.page.isNotBlank()) add(Triple("Lesson page", lesson.page, "html"))
        lesson.resources.forEach { add(Triple(it.title, it.uri, it.ext)) }
    }
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(files, key = { it.second }) { (title, uri, ext) ->
            val marked = videos.firstOrNull { it.meta.id == app.fileId(lessonId, uri) }?.highlights?.isNotEmpty() == true
            Surface(
                onClick = { openFile(ctx, app, lessonId, uri, title, ext) },
                shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(ext), null, Modifier.size(18.dp), tint = scheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                    if (marked) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(6.dp).clip(CircleShape).background(scheme.tertiary)) }
                }
            }
        }
    }
}

/**
 * The next lesson, as a card. [current] is the lesson you are in. Reading lessons and videos both open
 * from here, so a course flows on by itself. With [markDone], tapping it also marks this lesson done.
 */
@Composable
fun NextLessonCard(current: String, markDone: Boolean, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val next = remember(current) { app.nextLesson(current) }
    val scheme = MaterialTheme.colorScheme
    if (next == null) {
        val c = remember(current) { app.courses.find(current)?.first }
        if (c != null) Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHigh, modifier = modifier) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TaskAlt, null, tint = scheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("That is the last lesson of ${c.title}.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    Surface(
        onClick = {
            if (markDone && app.library.get(current)?.done != true) { app.setDone(current, true); app.finished(current) }
            app.nextReading.value = null
            nav.openVideo(next.id)
        },
        shape = RoundedCornerShape(20.dp), color = scheme.primaryContainer, modifier = modifier,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (next.media.isBlank()) Icons.AutoMirrored.Rounded.Article else Icons.Rounded.PlayArrow, null, tint = scheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (next.media.isBlank()) "Next: reading" else "Next lesson",
                    style = MaterialTheme.typography.labelMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Text(next.title, style = MaterialTheme.typography.titleSmall, color = scheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onPrimaryContainer)
        }
    }
}

/**
 * A file's text in the same reader as transcripts: highlight with a finger, tap a highlight for its note,
 * tags and colour. Your place is kept by word, so the file opens where you stopped.
 */
@Composable
fun DocTextReader(itemId: String, built: ArticleDoc.Built, note: String, footer: @Composable () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val settings by app.settings.state.collectAsState()
    val videos by app.library.videos.collectAsState()
    val item = videos.firstOrNull { it.meta.id == itemId } ?: return
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val d = built.doc
    val colors = remember(d, item.highlights) { Highlights.colorByWord(d.words, item.highlights) }
    val noted = remember(d, item.highlights) { Highlights.notedRuns(d.words, item.highlights) }
    val runs = remember(d, item.highlights) { Highlights.runs(d.words, item.highlights) }
    val typeface = remember(settings.readerFont, settings.customFontPath) {
        when (settings.readerFont) {
            "sans" -> ResourcesCompat.getFont(ctx, CoreR.font.atkinson_regular)
            "system" -> Typeface.DEFAULT
            "custom" -> customTypeface(settings.customFontPath) ?: ResourcesCompat.getFont(ctx, CoreR.font.newsreader)
            else -> ResourcesCompat.getFont(ctx, CoreR.font.newsreader)
        }
    }
    val dark = scheme.background.let { 0.2126f * it.red + 0.7152f * it.green + 0.0722f * it.blue } < 0.5f
    val style = ReaderStyle(
        textColor = scheme.onSurface.toArgb(),
        mutedColor = scheme.onSurfaceVariant.toArgb(),
        accent = scheme.primary.toArgb(),
        sizeSp = settings.readerFontSize,
        lineMult = settings.lineHeight,
        typeface = typeface,
        highlightAlpha = if (dark) 0.30f else 0.55f,
        holdSelects = settings.holdSelects,
        maxWidthPx = with(density) { (settings.maxWidth * settings.readerFontSize).dp.roundToPx() },
        highlighterOn = settings.highlighterEnabled,
        paragraphMult = settings.paragraphSpacing,
        letterEm = settings.letterSpacing,
        sidePx = with(density) { settings.sideMargin.dp.roundToPx() },
        justify = settings.justify,
        hyphenate = settings.hyphenate,
    )
    val screenW = ctx.resources.displayMetrics.widthPixels
    val imageWidth = minOf(style.maxWidthPx, screenW - with(density) { 36.dp.roundToPx() })
    val pictures = rememberPictures(built, imageWidth)

    var tview by remember { mutableStateOf<TranscriptView?>(null) }
    var editingId by remember(itemId) { mutableStateOf<String?>(null) }
    var atEnd by remember { mutableStateOf(false) }
    var word by remember(itemId) { mutableStateOf(item.positionSec.toInt()) }
    val restored = remember(itemId) { booleanArrayOf(false) }
    androidx.activity.compose.BackHandler(enabled = editingId != null) { editingId = null }
    LaunchedEffect(editingId) { if (editingId == null) tview?.stopResize() }
    // The place is kept a moment after you stop scrolling, not on every pixel.
    LaunchedEffect(itemId) {
        androidx.compose.runtime.snapshotFlow { word }.drop(1).debounce(800).collect { w ->
            app.library.edit(itemId) { it.copy(positionSec = w.toDouble(), playedAt = System.currentTimeMillis()) }
        }
    }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (note.isNotBlank()) Text(
                note, Modifier.fillMaxWidth().background(scheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
            )
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c -> TranscriptView(c).also { tview = it } },
                    update = { tv ->
                        tv.onSeek = { }
                        tv.onScrollDelta = {
                            atEnd = tv.progress() > 0.97f
                            tv.firstVisibleWord().let { w -> if (w >= 0 && restored[0]) word = w }
                        }
                        tv.currentColor = settings.highlightColor
                        tv.onTapWord = { w ->
                            val h = Highlights.at(item.highlights, w)
                            when {
                                h != null -> { editingId = h.id; tv.startResize(h.id, h.w0..h.w1); true }
                                editingId != null -> { editingId = null; true }
                                else -> true
                            }
                        }
                        tv.onPaint = { a, b, color ->
                            val next = Highlights.apply(item.highlights, d.words, d::plainText, a, b, color)
                            app.library.edit(itemId) { it.copy(highlights = next) }
                            app.settings.update { s -> s.copy(highlightColor = color) }
                            val made = next.firstOrNull { it.w0 == minOf(a, b) && it.w1 == maxOf(a, b) }
                            editingId = made?.id
                            if (made != null) tv.post { tv.startResize(made.id, made.w0..made.w1) }
                        }
                        tv.onResize = { id, a, b -> app.library.edit(itemId) { it.copy(highlights = Highlights.resize(it.highlights, d.words, d::plainText, id, a, b)) } }
                        tv.onErase = { a, b -> app.library.edit(itemId) { it.copy(highlights = Highlights.erase(it.highlights, d.words, d::plainText, a, b)) } }
                        tv.bind(d, style, built.styles, pictures, imageWidth)
                        tv.applyHighlights(colors, runs)
                        tv.applyNotes(noted)
                        if (!restored[0]) {
                            restored[0] = true
                            val w = item.positionSec.toInt()
                            if (w > 0) tv.post { tv.scrollToWord(w) }
                        }
                    },
                )
                // The end of the file: what comes next.
                androidx.compose.animation.AnimatedVisibility(
                    visible = atEnd && editingId == null,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                ) { footer() }
                androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            val editing = item.highlights.firstOrNull { it.id == editingId }
            AnimatedVisibility(visible = editing != null) {
                var last by remember { mutableStateOf(editing) }
                if (editing != null) last = editing
                last?.let { h ->
                    val vault = settings.linkedVault(settings.vault) ?: settings.vaultFolders.keys.firstOrNull()
                    HighlightNoteBar(
                        h = h,
                        onNote = { text -> app.library.edit(itemId) { v -> v.copy(highlights = v.highlights.map { if (it.id == h.id) it.copy(note = text) else it }) } },
                        tags = h.tags,
                        onTags = { t -> app.library.edit(itemId) { v -> v.copy(highlights = v.highlights.map { if (it.id == h.id) it.copy(tags = t) else it }) } },
                        vault = vault,
                        suggestTags = remember(vault) { app.library.videos.value.flatMap { v -> v.highlights.flatMap { it.tags } }.distinct() },
                        onTyping = { },
                        onColor = { c ->
                            app.library.edit(itemId) { v -> v.copy(highlights = v.highlights.map { if (it.id == h.id) it.copy(color = c) else it }) }
                            app.settings.update { it.copy(highlightColor = c) }
                        },
                        onDelete = {
                            editingId = null
                            app.library.edit(itemId) { v -> v.copy(highlights = v.highlights.filterNot { it.id == h.id }) }
                            scope.launch {
                                val r = snackbar.showSnackbar("Highlight deleted", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) app.library.edit(itemId) { v -> v.copy(highlights = v.highlights + h) }
                            }
                        },
                        onPlay = null,
                        onClose = { editingId = null },
                    )
                }
            }
        }
    }
}

/** What is inside a zip file. Tap a file to open it in the viewer; it is taken out of the zip first. */
@Composable
fun ZipList(zip: java.io.File, lessonId: String) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val entries = remember(zip) { runCatching { DocText.zipEntries(zip) } }
    val list = entries.getOrNull()
    if (list == null) {
        Text("This zip file could not be read. It may be damaged.", Modifier.padding(24.dp), color = scheme.error)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("${list.size} files inside", Modifier.padding(start = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
        items(list, key = { it.path }) { e ->
            val name = e.path.substringAfterLast('/')
            val folder = e.path.substringBeforeLast('/', "")
            val ext = name.substringAfterLast('.', "").lowercase()
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainer)
                    .clickable {
                        runCatching {
                            val dir = java.io.File(ctx.cacheDir, "open/zip").also { it.mkdirs() }
                            val f = DocText.unzip(zip, e.path, dir)
                            DocNav.requests.value = DocRequest(lessonId, android.net.Uri.fromFile(f).toString(), name.substringBeforeLast('.'), ext)
                        }.onFailure { android.widget.Toast.makeText(ctx, "Could not take that file out of the zip.", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(fileIcon(ext), null, tint = scheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val bits = listOfNotNull(folder.takeIf { it.isNotBlank() }, if (e.size > 0) sizeLabel(e.size) else null)
                    if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (folder.isNotBlank()) Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}
