package app.obsilearn.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.obsi.core.data.ArticleDoc
import app.obsi.core.ui.CountTime
import app.obsilearn.ObsiLearnApp
import app.obsilearn.data.DocHtml
import app.obsilearn.data.DocText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** A file of a lesson to show inside the app. */
data class DocRequest(val lessonId: String, val uri: String, val title: String, val type: String = "")

/** The file viewer listens here, so any screen can open a lesson's file inside the app. */
object DocNav {
    val requests = MutableStateFlow<DocRequest?>(null)

    /** File types ObsiLearn shows itself. Every other type still opens here, with what can be read of it. */
    fun inApp(uri: String): Boolean = ext(uri).let { it == "pdf" || DocHtml.canShow(it) }

    fun ext(uri: String) = android.net.Uri.decode(uri).substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
}

/** The text of a file, ready for the reader, or why there is none. */
private class Read(val built: ArticleDoc.Built?, val note: String, val pages: Int)

/**
 * Shows a lesson's file inside the app. Most files show as text you can highlight, in the same reader as
 * transcripts. A PDF or a lesson page can also show as it really looks. A zip shows what is inside it.
 * A file nothing can read still says what it is, and offers another app.
 */
@Composable
fun DocScreen(req: DocRequest, onBack: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val found = remember(req.lessonId) { app.courses.find(req.lessonId) }
    val lesson = found?.third
    val sourceId = found?.first?.sourceId.orEmpty()
    val ext = req.type.lowercase().ifBlank { DocNav.ext(req.uri) }
    val itemId = remember(req) { app.fileItem(req.lessonId, req.uri, req.title, ext) ?: app.fileId(req.lessonId, req.uri) }
    val videos by app.library.videos.collectAsState()
    val done = videos.firstOrNull { it.meta.id == req.lessonId }?.done == true
    val reading = lesson != null && lesson.media.isBlank()

    val file by produceState<Result<File>?>(null, req.uri) {
        value = withContext(Dispatchers.IO) { runCatching { app.localFile(sourceId, req.uri) } }
    }
    // The text, built once off the main thread. Pictures in a lesson page are found next to it.
    val cleanPdf = app.learn.state.collectAsState().value.cleanPdf
    val read by produceState<Read?>(null, file, cleanPdf) {
        val f = file?.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            if (!DocText.maybe(ext)) return@withContext Read(null, "", 0)
            val out = runCatching {
                DocText.build(f, ext, File(ctx.cacheDir, "pdf-pictures"), cleanPdf) { src ->
                    runCatching { android.net.Uri.fromFile(app.localFile(sourceId, DocText.resolve(req.uri, src))).toString() }.getOrNull()
                }
            }.getOrNull()
            val built = out?.let { runCatching { DocText.article(it.html, req.title) }.getOrNull() }?.takeIf { it.doc.words.isNotEmpty() }
            if (built != null) {
                // Kept for search, and so a highlight's place reads as "40% in".
                runCatching { app.textIndex.putFile(itemId, built.doc.words.map { w -> built.doc.text.substring(w.charStart, w.charEnd) }) }
                app.library.edit(itemId) { it.copy(meta = it.meta.copy(words = built.doc.words.size)) }
            }
            val pages = if (ext == "pdf") runCatching { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } } }.getOrDefault(0) else 0
            Read(built, out?.note.orEmpty(), pages)
        }
    }

    // Text or the file's own look. A choice you made is kept for this file.
    val places by app.docPlaces.state.collectAsState()
    val hasOriginal = ext == "pdf" || ext in DocText.pages || (DocHtml.canShow(ext) && ext !in DocHtml.images)
    val r = read
    val mode = places[itemId]?.mode?.takeIf { it.isNotBlank() } ?: when {
        r?.built == null -> "original"
        // A PDF of slides reads better as pages; a PDF of writing reads better as text.
        ext == "pdf" -> if (r.built.doc.words.size >= 120 * r.pages.coerceAtLeast(1)) "text" else "original"
        else -> "text"
    }
    val showText = mode == "text" && r?.built != null
    // Reading a file counts as time spent, for the shared stats.
    CountTime(item = lesson?.title ?: req.title)

    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(req.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(lesson?.title?.takeIf { it != req.title }, ext.uppercase().takeIf { it.isNotBlank() }).joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hasOriginal && r?.built != null) {
                IconButton(onClick = { app.docPlaces.set(itemId) { it.copy(mode = if (showText) "original" else "text") } }) {
                    if (showText) Icon(Icons.Rounded.AutoStories, if (ext == "pdf") "Show the pages" else "Show it as it looks")
                    else Icon(Icons.AutoMirrored.Rounded.Article, "Show as text you can highlight")
                }
            }
            if (ext == "pdf" && !showText) {
                val learn by app.learn.state.collectAsState()
                IconButton(onClick = { app.learn.update { it.copy(darkPdf = !it.darkPdf) } }) {
                    Icon(if (learn.darkPdf) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (learn.darkPdf) "Show the PDF light" else "Show the PDF dark")
                }
            }
            IconButton(onClick = { file?.getOrNull()?.let { openOutside(ctx, app, it) } }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in another app") }
            if (ext == "pdf" && r?.built != null) {
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }, shape = RoundedCornerShape(24.dp)) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("Hide repeated page text")
                                    Text(
                                        "Lines on every page, like a copyright note, and the page numbers. Not read aloud.",
                                        Modifier.width(220.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                    )
                                }
                            },
                            leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) },
                            trailingIcon = { if (cleanPdf) Icon(Icons.Rounded.Check, "On", tint = scheme.primary) },
                            onClick = { more = false; app.learn.update { it.copy(cleanPdf = !it.cleanPdf) } },
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val f = file
            when {
                f == null || (f.isSuccess && read == null) -> Loading(if (f == null) "Getting the file…" else "Reading the file…")
                f.isFailure -> Problem(
                    "Could not get the file",
                    app.explain(f.exceptionOrNull()),
                    retry = null,
                )
                ext == "zip" -> ZipList(f.getOrThrow(), req.lessonId)
                ext in DocHtml.images -> ZoomImage(f.getOrThrow())
                showText -> DocTextReader(itemId, r!!.built!!, r.note) { if (reading) NextLessonCard(req.lessonId, markDone = true, Modifier.fillMaxWidth()) }
                ext == "pdf" -> Column(Modifier.fillMaxSize()) {
                    // Say once how to highlight on the pages, when this PDF has text.
                    if (r?.built != null && android.os.Build.VERSION.SDK_INT >= 35) PagesHint()
                    PdfPages(f.getOrThrow(), itemId, r?.built)
                }
                hasOriginal -> PageView(f.getOrThrow(), ext) { src ->
                    runCatching { android.net.Uri.fromFile(app.localFile(sourceId, DocText.resolve(req.uri, src))).toString() }.getOrNull()
                }
                else -> Problem(
                    "ObsiLearn cannot read this file",
                    "It is a .$ext file" + (f.getOrNull()?.length()?.let { ", " + sizeLabel(it) } ?: "") + ". No text could be found in it. Another app on your phone may open it.",
                    retry = null,
                    action = "Open in another app" to { openOutside(ctx, app, f.getOrThrow()) },
                )
            }
        }
        // A reading lesson: say you are done, and go on.
        if (reading) {
            Row(
                Modifier.fillMaxWidth().background(scheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { if (done) app.setDone(req.lessonId, false) else { app.setDone(req.lessonId, true); app.finished(req.lessonId) } }) {
                    Icon(Icons.Rounded.TaskAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (done) "Done" else "Mark as done")
                }
                val next = remember(req.lessonId) { app.nextLesson(req.lessonId) }
                if (next != null) {
                    val nav = LocalNav.current
                    Button(onClick = {
                        if (!done) { app.setDone(req.lessonId, true); app.finished(req.lessonId) }
                        nav.openVideo(next.id)
                    }, modifier = Modifier.weight(1f)) {
                        Text("Next: " + next.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else Spacer(Modifier.navigationBarsPadding())
    }
}

private fun openOutside(ctx: android.content.Context, app: ObsiLearnApp, f: File) =
    app.openExternally(ctx, androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))

@Composable
private fun Loading(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(Modifier.width(140.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A problem, said plainly: what happened, what to do, and a button when there is one. */
@Composable
fun Problem(title: String, body: String, retry: (() -> Unit)?, action: Pair<String, () -> Unit>? = null) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (retry != null || action != null) Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (retry != null) Button(onClick = retry) { Text("Try again") }
            if (action != null) FilledTonalButton(onClick = action.second) { Text(action.first) }
        }
    }
}

/** One line over PDF pages: how to highlight on them. It can be closed for good. */
@Composable
private fun PagesHint() {
    val app = LocalApp.current
    val learn by app.learn.state.collectAsState()
    if (learn.pagesHintSeen) return
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BorderColor, null, Modifier.size(18.dp), tint = scheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Text("Hold a word, then drag, to highlight.", Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer)
            IconButton(onClick = { app.learn.update { it.copy(pagesHintSeen = true) } }) { Icon(Icons.Rounded.Close, "Do not show this again", tint = scheme.onSecondaryContainer) }
        }
    }
}

/**
 * Every page of a PDF, one under the other, as wide as the screen. One finger scrolls; two fingers zoom
 * and move. Double tap zooms in and out. Drawn pages are kept, so scrolling back is instant. The page you
 * were on is kept for next time.
 */
@Composable
private fun PdfPages(file: File, itemId: String, built: app.obsi.core.data.ArticleDoc.Built?) {
    val app = LocalApp.current
    val scheme = MaterialTheme.colorScheme
    val dark = app.learn.state.collectAsState().value.darkPdf
    val filter = remember(dark) { if (dark) androidx.compose.ui.graphics.ColorFilter.colorMatrix(darkMatrix()) else null }
    val opened = remember(file) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fd to PdfRenderer(fd)
        }
    }
    DisposableEffect(opened) { onDispose { opened.getOrNull()?.let { (fd, r) -> runCatching { r.close(); fd.close() } } } }
    val pdf = opened.getOrNull()
    if (pdf == null) {
        Problem("This PDF could not be opened", "It may be damaged, or locked with a password. Another app may open it.", retry = null)
        return
    }
    val renderer = pdf.second
    // The renderer draws one page at a time.
    val lock = remember(renderer) { Mutex() }
    val sizes = remember(renderer) { (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { it.width to it.height } } }
    // About 48 MB of drawn pages: plenty to scroll back and forth without drawing again.
    val cache = remember(renderer) { object : android.util.LruCache<Int, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(key: Int, value: Bitmap) = value.byteCount } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val videos by app.library.videos.collectAsState()
    val highlights = videos.firstOrNull { it.meta.id == itemId }?.highlights.orEmpty()
    var editingId by remember(itemId) { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled = editingId != null) { editingId = null }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    // Where each word is on its page, so the pages can be highlighted. Only Android 15 and up read PDF text.
    val words by produceState<app.obsilearn.data.PdfWords?>(null, renderer, built) {
        if (built == null || android.os.Build.VERSION.SDK_INT < 35) return@produceState
        value = withContext(Dispatchers.IO) { lock.withLock { runCatching { PdfMarks.words(renderer, built) }.getOrNull() } }
    }
    val start = remember(itemId) { app.docPlaces.of(itemId).page.coerceIn(0, (sizes.size - 1).coerceAtLeast(0)) }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    LaunchedEffect(itemId) {
        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex }.drop(1).debounce(600).collect { p ->
            app.docPlaces.set(itemId) { it.copy(page = p) }
            // Reading pages counts as reading: the place in words moves with the page, never back.
            app.library.get(itemId)?.meta?.words?.takeIf { it > 0 && sizes.isNotEmpty() }?.let { w ->
                val at = w * (p + 1.0) / sizes.size
                if (at > (app.library.get(itemId)?.positionSec ?: 0.0)) app.library.edit(itemId) { it.copy(positionSec = at) }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0E0E0E))) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
                .pointerInput(widthPx) {
                    // Only two fingers zoom. One finger is left alone, so the list scrolls smoothly.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) {
                                val zoom = e.calculateZoom()
                                val pan = e.calculatePan()
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                val max = (scale - 1f) * widthPx / 2f
                                offsetX = if (scale == 1f) 0f else (offsetX + pan.x).coerceIn(-max, max)
                                e.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (e.changes.any { it.pressed })
                    }
                }
                .pointerInput(widthPx) {
                    detectTapGestures(onDoubleTap = { if (scale > 1.05f) { scale = 1f; offsetX = 0f } else scale = 2f })
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX },
        ) {
            items(sizes.size, key = { it }) { i ->
                val (w, h) = sizes[i]
                val bmp by produceState(cache.get(i), i, widthPx) {
                    if (value != null) return@produceState
                    value = withContext(Dispatchers.IO) {
                        lock.withLock {
                            cache.get(i) ?: runCatching {
                                // One and a half times the screen, so text stays sharp at a light zoom.
                                val outW = (widthPx * 3 / 2).coerceAtMost(2000)
                                val outH = (outW.toFloat() * h / w).toInt().coerceAtLeast(1)
                                renderer.openPage(i).use { p ->
                                    Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(android.graphics.Color.WHITE)
                                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()?.also { cache.put(i, it) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(4.dp)).background(if (dark) Color(0xFF1A1A1A) else Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), colorFilter = filter) }
                    val map = words
                    if (map != null && built != null && android.os.Build.VERSION.SDK_INT >= 35) PageMarks(
                        renderer, lock, i, w, h, map, built, itemId, highlights, editingId,
                        onEdit = { editingId = it },
                        onDoubleTap = { if (scale > 1.05f) { scale = 1f; offsetX = 0f } else scale = 2f },
                    )
                }
            }
        }
        val page = state.firstVisibleItemIndex + 1
        Text(
            "$page / ${sizes.size}",
            Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
        androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    FileHighlightBar(itemId, editingId, { editingId = null }, snackbar)
    }
}

/** A picture, with pinch and double tap to zoom. */
@Composable
private fun ZoomImage(file: File) {
    val bmp = remember(file) { runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() }
    if (bmp == null) { Problem("This picture could not be shown", "It may be damaged, or a type this phone cannot draw.", retry = null); return }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    off = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else off + pan
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; off = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f }) },
        contentAlignment = Alignment.Center,
    ) {
        Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y })
    }
}

/**
 * Dark PDF pages: flip light and dark, then turn the colours half way round so red stays red and blue stays blue.
 * White paper becomes a soft black, not pure black, so it is easy on the eyes.
 */
private fun darkMatrix(): androidx.compose.ui.graphics.ColorMatrix {
    val invert = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val hue = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
        0.426f, 0.430f, 0.144f, 0f, 0f,
        0.426f, 1.430f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
    // Lift black a little and keep white text a little soft: 0..255 maps to 26..230.
    val soften = androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
        0.8f, 0f, 0f, 0f, 26f,
        0f, 0.8f, 0f, 0f, 26f,
        0f, 0f, 0.8f, 0f, 26f,
        0f, 0f, 0f, 1f, 0f,
    ))
    hue.timesAssign(invert)
    soften.timesAssign(hue)
    return soften
}

/**
 * A file as it really looks, in a web view: a lesson page with its pictures, or a document laid out.
 * In a dark theme the page is turned dark too, with its pictures kept as they are.
 */
@Composable
private fun PageView(file: File, ext: String, picture: (String) -> String?) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.let { 0.2126f * it.red + 0.7152f * it.green + 0.0722f * it.blue } < 0.5f
    val html by produceState<String?>(null, file, dark) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (ext in DocText.pages) {
                    val doc = org.jsoup.Jsoup.parse(file.readText())
                    doc.select("img[src]").forEach { img ->
                        val src = img.attr("src")
                        if (!src.startsWith("data:") && !src.startsWith("http")) picture(src)?.let { img.attr("src", it) }
                    }
                    doc.select("script").remove()
                    if (dark) doc.head().append(
                        "<style>html{background:#141414;filter:invert(1) hue-rotate(180deg)} img,video,picture,svg{filter:invert(1) hue-rotate(180deg)}</style>",
                    )
                    doc.outerHtml()
                } else DocHtml.build(file, ext, dark)
            }.getOrElse { DocHtml.page("<p>Could not read this file: " + DocHtml.esc(it.message ?: "") + "</p>", dark) }
                ?: DocHtml.page("<p>This file type cannot be shown.</p>", dark)
        }
    }
    val h = html
    if (h == null) { Loading("Laying out the page…"); return }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            android.webkit.WebView(c).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.allowFileAccess = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                settings.javaScriptEnabled = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = ext in DocText.pages
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: android.webkit.WebView, r: android.webkit.WebResourceRequest): Boolean {
                        val u = r.url
                        if (u.scheme == "http" || u.scheme == "https") {
                            runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            return true
                        }
                        return false
                    }
                }
            }
        },
        update = { w ->
            if (w.tag == h) return@AndroidView
            w.tag = h
            w.loadDataWithBaseURL(android.net.Uri.fromFile(file.parentFile ?: file).toString() + "/", h, "text/html", "utf-8", null)
        },
    )
}
