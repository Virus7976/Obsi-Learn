package app.obsilearn.ui

import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import app.obsi.core.data.ArticleDoc
import app.obsi.core.data.Highlight
import app.obsi.core.data.HighlightColors
import app.obsi.core.data.Highlights
import app.obsilearn.data.PdfWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Highlighting on a PDF's pages. Android 15 and up can read where a PDF's text is. */
@RequiresApi(35)
internal object PdfMarks {
    /** Where each word of the text view is on the pages. Reads every page's text once. */
    fun words(renderer: PdfRenderer, built: ArticleDoc.Built): PdfWords {
        val d = built.doc
        val pages = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { p -> p.textContents.joinToString("\n") { it.text } } }
        return PdfWords.match(d.words.map { d.text.substring(it.charStart, it.charEnd) }, pages)
    }

    /** The boxes around letters [from] to [to] on a page, in page points. */
    fun boxes(p: PdfRenderer.Page, from: Int, to: Int): List<RectF> =
        runCatching { p.selectContent(SelectionBoundary(from), SelectionBoundary(to)) }.getOrNull()
            ?.selectedTextContents?.flatMap { it.bounds }.orEmpty()

    /**
     * The letter under a point. Android gives no letter number for a selection made from a point, so
     * select from the page start to the point and count the letters. The count includes the letter under it.
     */
    private fun letterAt(p: PdfRenderer.Page, pt: Point): Int? =
        runCatching { p.selectContent(SelectionBoundary(0), SelectionBoundary(pt)) }.getOrNull()
            ?.selectedTextContents?.sumOf { it.text.length }?.takeIf { it > 0 }

    /** The whole words between two points on [page]. */
    fun wordsBetween(p: PdfRenderer.Page, page: Int, words: PdfWords, a: Point, b: Point): IntRange? {
        val x = letterAt(p, a) ?: return null
        val y = letterAt(p, b) ?: return null
        return words.words(page, minOf(x, y) - 1, maxOf(x, y))
    }

    /** The boxes around whole words [w] on [page]. */
    fun wordBoxes(p: PdfRenderer.Page, page: Int, words: PdfWords, w: IntRange): List<RectF> {
        val l = words.letters(page, w.first, w.last) ?: return emptyList()
        return boxes(p, l.first, l.last + 1)
    }
}

/**
 * Highlights drawn over one page, and the touch to make them: hold a word, then drag to the last word.
 * A tap on a highlight opens its note. The words are saved by the same numbers as in the text view, so a
 * highlight shows in both, and goes to review, search and Obsidian like every other.
 */
@RequiresApi(35)
@Composable
internal fun PageMarks(
    renderer: PdfRenderer, lock: Mutex, page: Int, pageW: Int, pageH: Int,
    words: PdfWords, built: ArticleDoc.Built, itemId: String, highlights: List<Highlight>,
    editingId: String?, onEdit: (String?) -> Unit, onDoubleTap: () -> Unit,
) {
    val app = LocalApp.current
    val settings by app.settings.state.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val mine = remember(highlights, words, page) { highlights.filter { it.w0 >= 0 && page in words.pages(it.w0, it.w1) } }
    val boxes by produceState(emptyList<Pair<Highlight, List<RectF>>>(), mine) {
        value = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    renderer.openPage(page).use { p ->
                        mine.mapNotNull { h ->
                            val l = words.letters(page, h.w0, h.w1) ?: return@mapNotNull null
                            PdfMarks.boxes(p, l.first, l.last + 1).takeIf { it.isNotEmpty() }?.let { h to it }
                        }
                    }
                }.getOrDefault(emptyList())
            }
        }
    }
    // The selection while a finger drags: its two ends, in page points, and the whole words between them.
    var drag by remember { mutableStateOf<Pair<Point, Point>?>(null) }
    var picked by remember { mutableStateOf<IntRange?>(null) }
    var live by remember { mutableStateOf<List<RectF>>(emptyList()) }
    LaunchedEffect(drag) {
        val d = drag ?: run { live = emptyList(); return@LaunchedEffect }
        val (w, rs) = withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    renderer.openPage(page).use { p ->
                        val w = PdfMarks.wordsBetween(p, page, words, d.first, d.second)
                        w to (w?.let { PdfMarks.wordBoxes(p, page, words, it) }.orEmpty())
                    }
                }.getOrDefault(null to emptyList())
            }
        }
        picked = w
        live = rs
    }

    fun commit(range: IntRange) {
        val doc = built.doc
        val now = app.library.get(itemId)?.highlights ?: return
        val next = Highlights.apply(now, doc.words, doc::plainText, range.first, range.last, settings.highlightColor)
        app.library.edit(itemId) { it.copy(highlights = next) }
        onEdit(next.firstOrNull { it.w0 == range.first && it.w1 == range.last }?.id)
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(page, words) {
                fun pt(o: Offset) = Point((o.x * pageW / size.width).toInt(), (o.y * pageH / size.height).toInt())
                detectDragGesturesAfterLongPress(
                    onDragStart = { o -> haptic.performHapticFeedback(HapticFeedbackType.LongPress); val p = pt(o); picked = null; drag = p to p },
                    onDrag = { change, _ -> change.consume(); drag = drag?.let { it.first to pt(change.position) } },
                    onDragEnd = {
                        val d = drag
                        drag = null
                        // The words the finger let go on. Work them out once more if the last move is not in yet.
                        if (d != null) scope.launch {
                            val w = withContext(Dispatchers.IO) {
                                lock.withLock { runCatching { renderer.openPage(page).use { p -> PdfMarks.wordsBetween(p, page, words, d.first, d.second) } }.getOrNull() }
                            } ?: picked
                            picked = null
                            if (w != null) commit(w)
                        }
                    },
                    onDragCancel = { drag = null; picked = null },
                )
            }
            .pointerInput(page, boxes) {
                detectTapGestures(
                    onDoubleTap = { onDoubleTap() },
                    onTap = { o ->
                        val x = o.x * pageW / size.width
                        val y = o.y * pageH / size.height
                        // A little slack around each box, so a tap near a thin line still finds it.
                        val hit = boxes.firstOrNull { (_, rs) -> rs.any { RectF(it).apply { inset(-6f, -6f) }.contains(x, y) } }
                        onEdit(hit?.first?.id)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sx = size.width / pageW
            val sy = size.height / pageH
            fun box(r: RectF, c: Color) = drawRoundRect(
                c, topLeft = Offset(r.left * sx - 1.5f, r.top * sy - 1f), size = Size(r.width() * sx + 3f, r.height() * sy + 2f),
                cornerRadius = CornerRadius(3f, 3f),
            )
            for ((h, rs) in boxes) {
                val c = Color(HighlightColors.getOrNull(h.color)?.ui ?: HighlightColors[0].ui)
                // The one being edited stands out a little more.
                rs.forEach { box(it, c.copy(alpha = if (h.id == editingId) 0.55f else 0.38f)) }
            }
            val c = Color(HighlightColors.getOrNull(settings.highlightColor)?.ui ?: HighlightColors[0].ui)
            live.forEach { box(it, c.copy(alpha = 0.45f)) }
        }
    }
}
