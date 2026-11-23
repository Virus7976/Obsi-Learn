package app.obsilearn.data

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipFile

/**
 * Turns a lesson's file into the HTML the text reader shows: the same reader as transcripts and
 * ObsiRead articles, so you can highlight in it. Each type gives what it can:
 *  - PDF: its text, page by page (Android 15 and up can read text out of a PDF).
 *  - Word, slides, sheets, OpenDocument, EPUB, Markdown, CSV: through [DocHtml].
 *  - A lesson page (.html): the page itself, with its pictures found next to it.
 *  - Old Word and PowerPoint (.doc, .ppt), RTF and unknown files: the text that can be pulled out.
 */
object DocText {
    /**
     * The file as an article to read and highlight. A first heading that only repeats the title is
     * dropped, so the title does not show twice. Search and the reader both use this, so word places match.
     */
    fun article(html: String, title: String): app.obsi.core.data.ArticleDoc.Built {
        val doc = org.jsoup.Jsoup.parse(html)
        val first = doc.body().select("h1,h2,h3,p,li").firstOrNull()
        fun norm(t: String) = t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        if (first != null && first.tagName().startsWith("h") && norm(first.text()) == norm(title)) first.remove()
        return app.obsi.core.data.ArticleDoc.build(doc.body().html(), title)
    }

    /** The result, and a line to say when it is only part of the file (an old format, for example). */
    class Out(val html: String, val note: String = "")

    val pages = setOf("html", "htm", "xhtml")
    /** Old Office files: no structure can be read, but the words can. */
    private val legacy = setOf("doc", "ppt", "xls", "pps", "dot", "wps", "pub")

    /**
     * [pics] is a folder to keep a PDF's pictures in. Without it, a PDF shows its text only.
     * [picture] turns a picture address from a lesson page into one the reader can load (a file on the
     * phone), or null when it cannot be found.
     */
    fun build(file: File, ext: String, pics: File? = null, cleanPdf: Boolean = true, picture: (String) -> String? = { null }): Out? = when {
        ext == "pdf" -> pdf(file, pics, cleanPdf)?.let { Out(it) }
        ext in pages -> Out(page(file.readText(), picture))
        ext == "txt" || ext == "text" -> Out(paragraphs(file.readText()))
        ext == "rtf" -> Out(paragraphs(rtf(file.readText())), "Text only. Formatting from the RTF file is left out.")
        DocHtml.canShow(ext) && ext !in DocHtml.images -> DocHtml.body(file, ext)?.let { Out(it) }
        ext in legacy -> legacyText(file)?.let { Out(paragraphs(it), "Text only. This is an old ." + ext + " file, so pictures and layout are left out.") }
        else -> sniffText(file)?.let { Out(paragraphs(it), "Shown as plain text.") }
    }

    /** True when the reader can show text for this type, before trying. Zip files and pictures have their own views. */
    fun maybe(ext: String) = ext !in DocHtml.images && ext != "zip"

    // ---------- PDF ----------

    /** Can this phone read text out of a PDF? Android 15 and up can. */
    val pdfText: Boolean get() = Build.VERSION.SDK_INT >= 35

    /**
     * The PDF's text, one section per page. Null when the phone cannot read PDF text, or when the PDF has
     * almost no text (a scan): then the pages are the only way to show it.
     * With [clean], text printed on page after page ([PdfClean]) and the page labels are left out, and a
     * paragraph that runs over a page break is kept in one piece.
     */
    fun pdf(file: File, pics: File? = null, clean: Boolean = true): String? {
        if (Build.VERSION.SDK_INT < 35) return null
        val pages = pdfLines(file) ?: return null
        val chars = pages.sumOf { p -> p.sumOf { it.length } }
        if (pages.isEmpty() || chars < 60 * pages.size) return null
        val found = pics?.let { runCatching { PdfPictures.find(file, it) }.getOrNull() }.orEmpty()
        val repeated = if (clean) PdfClean.repeated(pages) else emptySet()
        return buildString {
            val part = StringBuilder()
            fun flush() { append(paragraphs(part.toString(), joinLines = true)); part.setLength(0) }
            pages.forEachIndexed { i, lines ->
                if (pages.size > 1 && !clean) { flush(); append("<h6>Page ${i + 1}</h6>") }
                // Each picture goes in before the line under it, so it sits where it is on the page.
                val left = found.filter { it.page == i }.sortedBy { it.line }.toMutableList()
                var pageStart = true
                lines.forEachIndexed { n, text ->
                    while (left.isNotEmpty() && left.first().line <= n) { flush(); append(img(left.removeAt(0))) }
                    if (PdfClean.key(text) in repeated) return@forEachIndexed
                    // Over a page break, text is joined only when the new page goes on mid-sentence (a small letter).
                    if (pageStart && text.trim().firstOrNull()?.isLowerCase() != true) flush()
                    pageStart = false
                    part.append(text).append('\n')
                }
                if (left.isNotEmpty()) { flush(); left.forEach { append(img(it)) } }
            }
            flush()
        }
    }

    private fun img(p: PdfPictures.Picture) = "<img src=\"" + android.net.Uri.fromFile(p.file) + "\">"

    /** Each page's lines of text. */
    private fun pdfLines(file: File): List<List<String>>? {
        if (Build.VERSION.SDK_INT < 35) return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r -> (0 until r.pageCount).map { i -> r.openPage(i).use { p -> PdfPictures.lines(p) } } }
            }
        }.getOrNull()
    }

    /** The text of each page, or null when it cannot be read. */
    fun pdfPages(file: File): List<String>? {
        if (Build.VERSION.SDK_INT < 35) return null
        return runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    (0 until r.pageCount).map { i -> r.openPage(i).use { p -> pageText(p) } }
                }
            }
        }.getOrNull()
    }

    @androidx.annotation.RequiresApi(35)
    private fun pageText(p: PdfRenderer.Page): String = p.textContents.joinToString("\n") { it.text }

    // ---------- text into paragraphs ----------

    /**
     * Plain text as paragraphs. A blank line starts a new one. With [joinLines] (PDF text), lines that run
     * on are joined, and a word broken with a hyphen at the end of a line is put back together.
     */
    fun paragraphs(text: String, joinLines: Boolean = false): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { it.trimEnd() }
        val out = StringBuilder()
        val para = StringBuilder()
        val longest = lines.map { it.trim().length }.sorted().let { if (it.isEmpty()) 0 else it[(it.size * 0.8).toInt().coerceAtMost(it.lastIndex)] }
        fun flush() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) {
                val bullet = Regex("""^([•●▪◦‣\-–*]|\d{1,3}[.)])\s+""").find(t)
                if (bullet != null) out.append("<ul><li>").append(DocHtml.esc(t.substring(bullet.range.last + 1))).append("</li></ul>")
                else out.append("<p>").append(DocHtml.esc(t)).append("</p>")
            }
            para.setLength(0)
        }
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) { flush(); continue }
            val startsItem = Regex("""^([•●▪◦‣\-–*]|\d{1,3}[.)])\s+""").containsMatchIn(line)
            if (startsItem) flush()
            if (!joinLines) {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line)
                continue
            }
            if (para.isEmpty()) para.append(line)
            else if (para.endsWith("-") && line.firstOrNull()?.isLowerCase() == true) { para.setLength(para.length - 1); para.append(line) }
            else { para.append(' ').append(line) }
            // A short line that ends a sentence ends the paragraph.
            if (line.length < longest * 0.7 && line.last() in ".!?:\"”") flush()
        }
        flush()
        return out.toString()
    }

    // ---------- lesson pages ----------

    /** A lesson page, with each picture pointed at a copy on the phone. Scripts and styles are dropped. */
    fun page(html: String, picture: (String) -> String?): String {
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("script, style, noscript, iframe, form, nav").remove()
        doc.select("img[src]").forEach { img ->
            val src = img.attr("src")
            val local = if (src.startsWith("data:") || src.startsWith("http")) src else picture(src)
            if (local == null) img.remove() else img.attr("src", local)
        }
        return doc.body().html()
    }

    /**
     * Where a picture from a page is: [src] next to the page at [pageUri]. Works for web addresses,
     * NAS paths and folders picked on the phone (whose addresses keep the path in their last part).
     */
    fun resolve(pageUri: String, src: String): String {
        val clean = android.net.Uri.decode(src.substringBefore('#').substringBefore('?'))
        if (pageUri.startsWith("content:") && pageUri.contains("/document/")) {
            val base = pageUri.substringBefore("/document/")
            val docId = android.net.Uri.decode(pageUri.substringAfter("/document/"))
            val dir = docId.substringBeforeLast('/', docId.substringBefore(':') + ":")
            val joined = join(dir, clean)
            return base + "/document/" + android.net.Uri.encode(joined)
        }
        val dir = pageUri.substringBeforeLast('/')
        return join(dir, clean)
    }

    private fun join(dir: String, rel: String): String {
        // A folder picked on the phone has ids like "primary:Course/Week 1"; its top is "primary:".
        val root = dir.takeIf { it.endsWith(":") }
        val parts = if (root != null) ArrayList() else ArrayList(dir.split('/'))
        for (p in rel.split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.size > (if (root != null) 0 else 1)) parts.removeAt(parts.lastIndex)
            else -> parts += p
        }
        return (root ?: "") + parts.joinToString("/")
    }

    // ---------- old and unknown files ----------

    /**
     * The words in an old Office file. These keep text as runs of plain characters, in one byte or
     * two bytes per letter. Runs of real words are kept; the rest is binary and is dropped.
     */
    fun legacyText(file: File): String? {
        val bytes = file.readBytes()
        val runs = LinkedHashSet<String>()
        // Two bytes per letter (UTF-16LE), as newer old Office files keep text.
        run {
            val sb = StringBuilder()
            var i = 0
            while (i + 1 < bytes.size) {
                val c = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                val ch = c.toChar()
                if (ch == '\r' || ch == '\n' || ch == '\u000b') sb.append('\n')
                else if (ch.isLetterOrDigit() || ch in " .,;:!?'\"()-–—’“”&%$/\t" ) sb.append(ch)
                else { keep(sb, runs); sb.setLength(0) }
                i += 2
            }
            keep(sb, runs)
        }
        // One byte per letter, as older files keep text.
        run {
            val sb = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                val ch = if (c in 0x80..0x9F) ' ' else c.toChar()
                if (c == 13 || c == 10 || c == 11) sb.append('\n')
                else if (c in 32..126 || c in 0xA0..0xFF) sb.append(ch)
                else { keep(sb, runs); sb.setLength(0) }
            }
            keep(sb, runs)
        }
        val text = runs.joinToString("\n\n").trim()
        return text.takeIf { it.length > 40 }
    }

    /** Keeps a run that reads like words: long enough, mostly letters and spaces, with real words in it. */
    private fun keep(sb: StringBuilder, into: MutableSet<String>) {
        val t = sb.toString().replace(Regex("[ \t]+"), " ").trim()
        if (t.length < 24) return
        val letters = t.count { it.isLetter() }
        val spaces = t.count { it == ' ' }
        if (letters < t.length * 0.6 || spaces < t.length / 14) return
        // Font names and style names are binary filler, not text.
        if (Regex("""(Times New Roman|Calibri|Arial|Normal|Default Paragraph Font|Microsoft|Heading \d|Table Grid)""").findAll(t).count() > 2) return
        into += t
    }

    /** RTF to plain text: control words and groups out, paragraph marks kept. */
    fun rtf(src: String): String {
        val noGroups = src.replace(Regex("""\{\\\*[^{}]*\}"""), "").replace(Regex("""\{\\(fonttbl|colortbl|stylesheet|info)[\s\S]*?\}\s*\}"""), "")
        return noGroups
            .replace(Regex("""\\par[d]?\b ?"""), "\n")
            .replace(Regex("""\\line\b ?"""), "\n")
            .replace(Regex("""\\'([0-9a-fA-F]{2})""")) { m -> m.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("""\\u(-?\d+)\??""")) { m -> m.groupValues[1].toInt().let { if (it < 0) it + 65536 else it }.toChar().toString() }
            .replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), "")
            .replace(Regex("""[{}]"""), "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /** A file of an unknown type that is really text: shown as text. Binary files give null. */
    fun sniffText(file: File): String? {
        val buf = ByteArray(64 * 1024)
        val n = file.inputStream().use { it.read(buf) }
        if (n <= 0) return null
        val head = buf.copyOf(n)
        val bad = head.count { val c = it.toInt() and 0xFF; c < 9 || c in 14..31 }
        if (bad > head.size / 100) return legacyText(file)
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ---------- zip ----------

    class Entry(val path: String, val size: Long)

    /** What is inside a zip file. Folders and system files are left out. */
    fun zipEntries(file: File): List<Entry> = ZipFile(file).use { z ->
        z.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX") && !it.name.substringAfterLast('/').startsWith(".") }
            .map { Entry(it.name, it.size) }.sortedBy { it.path.lowercase() }.toList()
    }

    /** Takes one file out of a zip into [dir], and returns it. */
    fun unzip(file: File, path: String, dir: File): File {
        val out = File(dir, Layout.id(file.path, path).take(8) + "-" + path.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_"))
        if (out.length() > 0) return out
        ZipFile(file).use { z -> z.getInputStream(z.getEntry(path)).use { i -> out.outputStream().use { o -> i.copyTo(o) } } }
        return out
    }
}
