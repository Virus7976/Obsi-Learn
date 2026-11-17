package app.obsilearn.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import app.obsilearn.data.DocHtml

/** A file of a lesson to show inside the app. */
data class DocRequest(val lessonId: String, val uri: String, val title: String, val type: String = "")

/** The file viewer listens here, so any screen can open a lesson's file inside the app. */
object DocNav {
    val requests = MutableStateFlow<DocRequest?>(null)

    /** File types ObsiLearn shows itself. The viewer still opens for the rest, with a button to another app. */
    fun inApp(uri: String): Boolean = ext(uri).let { it == "pdf" || DocHtml.canShow(it) }

    fun ext(uri: String) = android.net.Uri.decode(uri).substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
}

/** Shows a lesson's PDF, page or text inside the app. */
@Composable
fun DocScreen(req: DocRequest, onBack: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val found = remember(req.lessonId) { app.courses.find(req.lessonId) }
    val lesson = found?.third
    val videos by app.library.videos.collectAsState()
    val done = videos.firstOrNull { it.meta.id == req.lessonId }?.done == true
    val file by produceState<Result<File>?>(null, req.uri) {
        value = withContext(Dispatchers.IO) { runCatching { app.localFile(found?.first?.sourceId.orEmpty(), req.uri) } }
    }
    val ext = req.type.lowercase().ifBlank { DocNav.ext(req.uri) }

    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(req.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                lesson?.takeIf { it.title != req.title }?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (ext == "pdf") {
                val learn by app.learn.state.collectAsState()
                IconButton(onClick = { app.learn.update { it.copy(darkPdf = !it.darkPdf) } }) {
                    Icon(
                        if (learn.darkPdf) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        if (learn.darkPdf) "Show the PDF light" else "Show the PDF dark",
                    )
                }
            }
            IconButton(onClick = {
                file?.getOrNull()?.let { app.openExternally(ctx, androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it)) }
            }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in another app") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val f = file
            when {
                f == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                f.isFailure -> Text(
                    "Could not get the file. " + (f.exceptionOrNull()?.message ?: ""),
                    Modifier.align(Alignment.Center).padding(24.dp), color = scheme.error,
                )
                ext == "pdf" -> PdfPages(f.getOrThrow())
                ext == "html" || ext == "htm" || DocHtml.canShow(ext) -> PageView(f.getOrThrow(), ext)
                else -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ObsiLearn cannot show .$ext files yet.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(6.dp))
                    FilledTonalButton(onClick = {
                        app.openExternally(ctx, androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f.getOrThrow()))
                    }) { Text("Open in another app") }
                }
            }
        }
        // A lesson that is only reading is done when you say so.
        if (lesson != null && lesson.media.isBlank()) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { if (done) app.setDone(req.lessonId, false) else { app.setDone(req.lessonId, true); app.finished(req.lessonId) } }) {
                    Icon(Icons.Rounded.TaskAlt, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (done) "Done" else "Mark as done")
                }
            }
        } else Spacer(Modifier.navigationBarsPadding())
    }
}

/** Every page of a PDF, one under the other, as wide as the screen. Pinch to zoom. */
@Composable
private fun PdfPages(file: File) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalApp.current.learn.state.collectAsState().value.darkPdf
    val filter = remember(dark) { if (dark) androidx.compose.ui.graphics.ColorFilter.colorMatrix(darkMatrix()) else null }
    val opened = remember(file) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fd to PdfRenderer(fd)
        }
    }
    DisposableEffect(opened) { onDispose { opened.getOrNull()?.let { (fd, r) -> runCatching { r.close(); fd.close() } } } }
    val pdf = opened.getOrNull()?.second
    if (pdf == null) {
        Text("This PDF could not be opened. It may be damaged.", Modifier.padding(24.dp), color = scheme.error)
        return
    }
    // The renderer draws one page at a time.
    val lock = remember(pdf) { Mutex() }
    val sizes = remember(pdf) { (0 until pdf.pageCount).map { i -> pdf.openPage(i).use { it.width to it.height } } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0E0E0E))) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val state = rememberLazyListState()
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        val max = (scale - 1f) * widthPx / 2f
                        offsetX = if (scale == 1f) 0f else (offsetX + pan.x).coerceIn(-max, max)
                    }
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offsetX },
        ) {
            items(sizes.indices.toList(), key = { it }) { i ->
                val (w, h) = sizes[i]
                val bmp by produceState<Bitmap?>(null, i, widthPx) {
                    value = withContext(Dispatchers.IO) {
                        lock.withLock {
                            runCatching {
                                // Twice the screen width, so text stays sharp when you zoom.
                                val outW = (widthPx * 2).coerceAtMost(2400)
                                val outH = (outW.toFloat() * h / w).toInt().coerceAtLeast(1)
                                pdf.openPage(i).use { p ->
                                    Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
                                        it.eraseColor(android.graphics.Color.WHITE)
                                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                                }
                            }.getOrNull()
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(4.dp)).background(if (dark) Color(0xFF1A1A1A) else Color.White)) {
                    bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), colorFilter = filter) }
                }
            }
        }
        val page by remember { derivedStateOf { state.firstVisibleItemIndex + 1 } }
        Text(
            "$page / ${sizes.size}",
            Modifier.align(Alignment.BottomCenter).padding(12.dp).clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
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

/** A lesson page (.html) or a text file, in a web view with the app's dark colours for text. */
@Composable
private fun PageView(file: File, ext: String) {
    val loaded = remember { mutableStateOf(false) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            android.webkit.WebView(c).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.allowFileAccess = true
                settings.javaScriptEnabled = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = ext == "html" || ext == "htm"
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
            if (loaded.value) return@AndroidView
            loaded.value = true
            if (ext == "html" || ext == "htm") { w.loadUrl(android.net.Uri.fromFile(file).toString()); return@AndroidView }
            // Built off the main thread: a big Word file or deck takes a moment.
            Thread {
                val html = runCatching { DocHtml.build(file, ext, dark = true) }
                    .getOrElse { DocHtml.page("<p>Could not read this file: " + DocHtml.esc(it.message ?: "") + "</p>", true) }
                    ?: DocHtml.page("<p>This file type cannot be shown.</p>", true)
                w.post { w.loadDataWithBaseURL(android.net.Uri.fromFile(file.parentFile ?: file).toString() + "/", html, "text/html", "utf-8", null) }
            }.start()
        },
    )
}

