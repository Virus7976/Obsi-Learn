package app.obsilearn

import app.obsilearn.data.PdfClean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCleanTest {
    private fun page(n: Int, body: String) = listOf(
        "Elucidatory Axioms",
        body,
        "© Imperium Acquisition - All rights reserved.",
        "Page $n of 12",
    )

    private val pages = (1..12).map { page(it, "Real text that is only on page $it, about topic ${'A' + it}.") }

    @Test
    fun linesOnEveryPageAreFound() {
        val r = PdfClean.repeated(pages)
        assertTrue(PdfClean.key("© Imperium Acquisition - All rights reserved.") in r)
        assertTrue(PdfClean.key("Elucidatory Axioms") in r)
        // Every number counts as the same, so page numbers repeat too.
        assertTrue(PdfClean.key("Page 7 of 12") in r)
    }

    @Test
    fun realTextStays() {
        val r = PdfClean.repeated(pages)
        pages.forEach { p -> assertFalse(PdfClean.key(p[1]) in r) }
    }

    @Test
    fun aShortPdfIsLeftAlone() {
        assertEquals(emptySet<String>(), PdfClean.repeated(pages.take(2)))
    }
}
