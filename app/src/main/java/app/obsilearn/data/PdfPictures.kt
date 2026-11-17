package app.obsilearn.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Finds the pictures on the pages of a PDF, so the text view can show them too.
 *
 * Android cannot give a PDF's pictures directly. So each page is drawn, its text is painted over in
 * white, and what is left is ink that is not text. Big, solid areas of that ink are pictures. Thin
 * lines, table borders and small marks are left out.
 *
 * The pictures are kept in a folder, so a PDF is only searched once.
 */
object PdfPictures {
    /** A picture: its page, the line of that page it goes before, and the saved file. */
    class Picture(val page: Int, val line: Int, val file: File)

    /** A page's lines of text. The text view and the picture finder both count lines this way. */
    @androidx.annotation.RequiresApi(35)
    fun lines(p: PdfRenderer.Page): List<String> = p.textContents.flatMap { it.text.split('\n') }

    /**
     * Where each line is on the page, in page points. Android gives a page's text without its place,
     * so each line is searched for, and the match nearest to where the line is in the text is used.
     */
    @androidx.annotation.RequiresApi(35)
    private fun boxes(p: PdfRenderer.Page, lines: List<String>): List<List<android.graphics.RectF>> {
        var at = 0
        return lines.map { line ->
            val start = at
            at += line.length + 1
            val q = line.trim().take(60)
            if (q.length < 2) return@map emptyList()
            fun near(s: String) = runCatching { p.searchText(s) }.getOrNull().orEmpty()
                .minByOrNull { kotlin.math.abs(it.textStartIndex - start) }?.bounds.orEmpty()
            near(q).ifEmpty {
                // Not found whole (odd letters, or a long line): find its first and last words, and cover between.
                val words = q.split(' ').filter { it.isNotBlank() }
                val ends = near(words.take(3).joinToString(" ")) + near(words.takeLast(3).joinToString(" "))
                // Both ends must be on one line, or the box could cover a picture.
                val tall = ends.maxOfOrNull { it.height() } ?: 0f
                if (ends.isEmpty() || ends.maxOf { it.bottom } - ends.minOf { it.top } > tall * 2) ends
                else listOf(android.graphics.RectF(ends.minOf { it.left }, ends.minOf { it.top }, ends.maxOf { it.right }, ends.maxOf { it.bottom }))
            }
        }
    }

    private const val WIDTH = 1000
    private const val BLOCK = 8

    fun find(pdf: File, root: File): List<Picture> {
        if (Build.VERSION.SDK_INT < 35) return emptyList()
        val dir = File(root, Layout.id(pdf.path, "v3:" + pdf.length() + ":" + pdf.lastModified()).take(16))
        val list = File(dir, "pictures.txt")
        if (list.exists()) return read(list, dir)
        dir.mkdirs()
        val out = ArrayList<Picture>()
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                for (i in 0 until r.pageCount) r.openPage(i).use { p -> out += page(p, i, dir) }
            }
        }
        list.writeText(out.joinToString("\n") { "${it.page}\t${it.line}\t${it.file.name}" })
        return out
    }

    private fun read(list: File, dir: File) = list.readLines().mapNotNull { l ->
        val (p, t, n) = l.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
        Picture(p.toIntOrNull() ?: return@mapNotNull null, t.toIntOrNull() ?: 0, File(dir, n)).takeIf { it.file.exists() }
    }

    @androidx.annotation.RequiresApi(35)
    private fun page(p: PdfRenderer.Page, index: Int, dir: File): List<Picture> {
        val scale = WIDTH / p.width.toFloat().coerceAtLeast(1f)
        val w = WIDTH
        val h = (p.height * scale).toInt().coerceIn(1, WIDTH * 4)
        val page = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        page.eraseColor(android.graphics.Color.WHITE)
        p.render(page, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        // A copy with the text painted out.
        val bare = page.copy(Bitmap.Config.ARGB_8888, true)
        val white = Paint().apply { color = android.graphics.Color.WHITE }
        val lines = lines(p)
        val where = boxes(p, lines)
        Canvas(bare).apply {
            where.forEach { line -> line.forEach { r -> drawRect(r.left * scale - 3, r.top * scale - 3, r.right * scale + 3, r.bottom * scale + 3, white) } }
        }
        // The top of each line, for placing pictures between lines. A line not found takes the one before it.
        var last = 0f
        val tops = where.map { b -> (b.minOfOrNull { it.top }?.times(scale) ?: last).also { last = it } }
        val px = IntArray(w * h)
        bare.getPixels(px, 0, w, 0, 0, w, h)
        bare.recycle()

        // Which blocks of the page have ink, and how much.
        val gw = (w + BLOCK - 1) / BLOCK
        val gh = (h + BLOCK - 1) / BLOCK
        val ink = IntArray(gw * gh)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (r < 230 || g < 230 || b < 230) ink[(y / BLOCK) * gw + x / BLOCK]++
        }

        // Join blocks with ink that touch, or nearly touch, into areas.
        val seen = BooleanArray(gw * gh)
        val boxes = ArrayList<IntArray>()
        val stack = ArrayDeque<Int>()
        for (start in 0 until gw * gh) {
            if (ink[start] == 0 || seen[start]) continue
            var x0 = gw; var y0 = gh; var x1 = -1; var y1 = -1
            var inked = 0L
            seen[start] = true
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val at = stack.removeLast()
                val ax = at % gw; val ay = at / gw
                x0 = minOf(x0, ax); y0 = minOf(y0, ay); x1 = maxOf(x1, ax); y1 = maxOf(y1, ay)
                inked += ink[at]
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = ax + dx; val ny = ay + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val n = ny * gw + nx
                    if (ink[n] > 0 && !seen[n]) { seen[n] = true; stack.addLast(n) }
                }
            }
            val bw = (x1 - x0 + 1) * BLOCK
            val bh = (y1 - y0 + 1) * BLOCK
            val fill = inked.toFloat() / (bw.toLong() * bh)
            // A picture is big enough to see, not a thin line, mostly filled, and not the whole page (a background).
            if (bw >= w * 0.12f && bh >= w * 0.08f && fill >= 0.12f && bw * bh.toLong() < w * h.toLong() * 0.85f) {
                boxes += intArrayOf(x0 * BLOCK, y0 * BLOCK, minOf(w, (x1 + 1) * BLOCK), minOf(h, (y1 + 1) * BLOCK))
            }
        }

        val out = boxes.sortedBy { it[1] }.mapIndexed { n, b ->
            val crop = Bitmap.createBitmap(page, b[0], b[1], b[2] - b[0], b[3] - b[1])
            val f = File(dir, "p${index + 1}-${n + 1}.jpg")
            f.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            crop.recycle()
            // Before the first line that starts below the picture's top.
            val line = tops.indexOfFirst { it > b[1] }.let { if (it < 0) lines.size else it }
            Picture(index, line, f)
        }
        page.recycle()
        return out
    }
}
