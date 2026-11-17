package app.obsilearn

import app.obsilearn.data.DocText
import app.obsilearn.data.SavedVideo
import app.obsilearn.data.TextIndex
import app.obsilearn.data.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocTextTest {
    @Test fun pdfLinesBecomeParagraphs() {
        val text = "This is the first line of a long\nparagraph that runs on to the next\nline and ends here.\nShort end.\n\nA new para-\ngraph starts."
        val html = DocText.paragraphs(text, joinLines = true)
        assertTrue(html, html.contains("<p>This is the first line of a long paragraph that runs on to the next line and ends here."))
        assertTrue(html, html.contains("<p>A new paragraph starts.</p>"))
    }

    @Test fun bulletsBecomeItems() {
        val html = DocText.paragraphs("Steps:\n• Open the app\n• Pick a course\n1. Then read")
        assertTrue(html, html.contains("<li>Open the app</li>"))
        assertTrue(html, html.contains("<li>Then read</li>"))
    }

    @Test fun rtfKeepsOnlyTheWords() {
        val rtf = """{\rtf1\ansi{\fonttbl{\f0 Times New Roman;}}\f0\fs24 Hello \b world\b0 .\par Second line\'e9.}"""
        val t = DocText.rtf(rtf)
        assertTrue(t, t.contains("Hello world"))
        assertTrue(t, t.contains("Second lineé"))
        assertTrue(t, !t.contains("fonttbl") && !t.contains("Times New Roman"))
    }

    @Test fun oldWordFileGivesItsText() {
        val f = File.createTempFile("old", ".doc")
        // Binary filler around a UTF-16 run, the way old Office files keep text.
        val words = "The quarterly plan is to grow sales by ten percent in every region this year."
        val bytes = ByteArray(200) { (it * 7).toByte() } + words.toByteArray(Charsets.UTF_16LE) + ByteArray(120) { 0 }
        f.writeBytes(bytes)
        val t = DocText.legacyText(f)
        assertNotNull(t)
        assertTrue(t!!, t.contains("grow sales by ten percent"))
        f.delete()
    }

    @Test fun binaryHasNoText() {
        val f = File.createTempFile("bin", ".dat")
        f.writeBytes(ByteArray(4000) { (it % 7).toByte() })
        assertNull(DocText.sniffText(f))
        f.delete()
    }

    @Test fun searchFindsAPhraseInAFileAndItsWord() {
        val dir = Files.createTempDirectory("idx").toFile()
        val idx = TextIndex(dir, { null })
        idx.putFile("f1", "one two three find me here and more".split(' '))
        val item = SavedVideo(VideoMeta("f1", "Workbook", "Course", file = "x.pdf", ext = "pdf"))
        val hits = idx.search("find me", listOf(item))
        assertEquals(1, hits.size)
        assertEquals(3.0, hits[0].at, 0.0)
        dir.deleteRecursively()
    }
}
