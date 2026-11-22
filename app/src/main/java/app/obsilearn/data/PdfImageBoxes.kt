package app.obsilearn.data

import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File

/**
 * Where each picture is drawn on each page of a PDF, read from the PDF itself (with PdfBox). Android's
 * own PDF reader can say a page has pictures, but not where they are.
 */
object PdfImageBoxes {
    /**
     * For each page, the boxes of its pictures in page points, measured from the top left of the page as
     * Android draws it. Null when the PDF cannot be read this way, so the caller can fall back.
     */
    fun find(pdf: File): List<List<RectF>>? = runCatching {
        PDDocument.load(pdf).use { doc ->
            doc.pages.map { page ->
                if (page.rotation % 360 != 0) return@runCatching null
                val crop = page.cropBox
                val engine = Engine()
                engine.processPage(page)
                engine.boxes.mapNotNull { r ->
                    // PDF counts up from the bottom; the screen counts down from the top.
                    val out = RectF(r.left - crop.lowerLeftX, crop.upperRightY - r.bottom, r.right - crop.lowerLeftX, crop.upperRightY - r.top)
                    if (!out.intersect(0f, 0f, crop.width, crop.height)) null else out
                }
            }
        }
    }.getOrNull()

    /** Walks a page's drawing steps and notes where each picture lands. */
    private class Engine : PDFStreamEngine() {
        val boxes = ArrayList<RectF>()

        init {
            addOperator(Concatenate())
            addOperator(SetGraphicsStateParameters())
            addOperator(Save())
            addOperator(Restore())
            addOperator(SetMatrix())
        }

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName ?: return
                when (val x = runCatching { resources.getXObject(name) }.getOrNull()) {
                    is PDImageXObject -> {
                        // A picture fills the unit square of the current drawing space.
                        val m = graphicsState.currentTransformationMatrix
                        val pts = listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).map { (a, b) -> m.transformPoint(a, b) }
                        // Here "top" is the low PDF y; it is flipped to screen space by the caller.
                        boxes += RectF(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
                    }
                    // A form is a drawing in a drawing: walk into it, it can hold pictures too.
                    is PDFormXObject -> runCatching { showForm(x) }
                    else -> {}
                }
                return
            }
            super.processOperator(operator, operands)
        }
    }

    /**
     * Which picture boxes to show in the text view, page by page. Left out: small marks (icons, bullets),
     * and anything drawn at the same place on most pages (a logo or header). Pictures that overlap become
     * one, so no picture shows a cut-off piece of the next.
     */
    fun keep(pages: List<List<RectF>>, pageW: List<Float>, pageH: List<Float>): List<List<RectF>> {
        fun same(a: RectF, b: RectF) = kotlin.math.abs(a.left - b.left) < 3 && kotlin.math.abs(a.top - b.top) < 3 &&
            kotlin.math.abs(a.right - b.right) < 3 && kotlin.math.abs(a.bottom - b.bottom) < 3
        val all = pages.flatten()
        val repeated = { r: RectF -> pages.size >= 3 && pages.count { p -> p.any { same(it, r) } } >= maxOf(3, pages.size / 2) }
        return pages.mapIndexed { i, boxes ->
            val w = pageW.getOrElse(i) { 612f }
            val h = pageH.getOrElse(i) { 792f }
            val big = boxes.filter { it.width() >= w * 0.07f && it.height() >= h * 0.05f && !repeated(it) }
            val merged = ArrayList<RectF>()
            for (r in big.sortedBy { it.top }) {
                val hit = merged.firstOrNull { m -> RectF.intersects(m, r) }
                if (hit != null) hit.union(r) else merged += RectF(r)
            }
            // A join can make a box that now overlaps another: join again until none do.
            var again = true
            while (again) {
                again = false
                loop@ for (a in merged.indices) for (b in a + 1 until merged.size) {
                    if (RectF.intersects(merged[a], merged[b])) { merged[a].union(merged[b]); merged.removeAt(b); again = true; break@loop }
                }
            }
            if (all.isEmpty()) emptyList() else merged
        }
    }
}
