package app.obsilearn

import app.obsilearn.data.DocText
import app.obsilearn.data.PdfWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWordsTest {
    // Two pages as a PDF gives them: lines, a word broken with a hyphen, a page number at the foot.
    private val pages = listOf(
        "Chapter One\nThe reason it would matter is that writ-\ning is not just a way to convey ideas.\n1",
        "Talking about your ideas with other people\nis a good way to develop them.\n2",
    )

    /** The text view's words, built the way DocText.pdf builds them. */
    private fun viewWords(): List<String> {
        val html = buildString {
            pages.forEachIndexed { i, t -> append("<h6>Page ${i + 1}</h6>"); append(DocText.paragraphs(t, joinLines = true)) }
        }
        val d = DocText.article(html, "Test").doc
        return d.words.map { d.text.substring(it.charStart, it.charEnd) }
    }

    @Test fun everyMatchedWordIsTheSameWordOnThePage() {
        val words = viewWords()
        val map = PdfWords.match(words, pages)
        var matched = 0
        for (i in words.indices) {
            val s = map.spot(i) ?: continue
            matched++
            assertEquals("word $i", PdfWords.norm(words[i]), PdfWords.norm(pages[s.page].substring(s.start, s.end)))
        }
        // All but the "Page 1" / "Page 2" headings are found on the pages.
        assertTrue("matched $matched of ${words.size}", matched >= words.size - 4)
    }

    @Test fun aWordBrokenOverTwoLinesIsOneWord() {
        val words = viewWords()
        val i = words.indexOfFirst { PdfWords.norm(it) == "writing" }
        assertTrue(i >= 0)
        val s = assertNotNullSpot(PdfWords.match(words, pages).spot(i))
        assertEquals("writ-\ning", pages[0].substring(s.start, s.end))
    }

    @Test fun lettersAndWordsGoBothWays() {
        val words = viewWords()
        val map = PdfWords.match(words, pages)
        val a = words.indexOfFirst { it == "Talking" }
        val b = words.indexOfFirst { it == "ideas" && words.indexOf(it) != -1 && it.isNotEmpty() }.let { words.lastIndexOf("ideas") }
        val letters = map.letters(1, a, b)!!
        assertEquals("Talking about your ideas", pages[1].substring(letters.first, letters.last + 1))
        assertEquals(a..b, map.words(1, letters.first, letters.last + 1))
        assertEquals(setOf(1), map.pages(a, b))
        // A highlight across the page break is on both pages.
        assertEquals(setOf(0, 1), map.pages(a - 6, a))
        assertNull(map.letters(0, a, b))
    }

    private fun assertNotNullSpot(s: PdfWords.Spot?): PdfWords.Spot { assertNotNull(s); return s!! }
}
