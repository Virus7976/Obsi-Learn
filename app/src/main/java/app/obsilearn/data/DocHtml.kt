package app.obsilearn.data

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/**
 * Turns a course file into one HTML page that the app can show itself:
 * Word (.docx), slides (.pptx), sheets (.xlsx), OpenDocument text, Markdown, CSV, text and pictures.
 * The look is simple on purpose. Slides keep their text and pictures, not their exact layout.
 * Null means this type is not known, and only another app can open it.
 */
object DocHtml {
    val images = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "avif")
    val texts = setOf("txt", "text", "log", "json", "xml", "yaml", "yml", "srt", "vtt", "py", "js", "kt", "java", "c", "cpp", "cs", "css", "sh")
    private val known = images + texts + setOf("html", "htm", "md", "markdown", "csv", "tsv", "docx", "pptx", "xlsx", "odt", "epub")

    fun canShow(ext: String) = ext in known

    fun build(file: File, ext: String, dark: Boolean): String? {
        val body = when (ext) {
            in images -> "<img class=solo src=\"${android.net.Uri.fromFile(file)}\">"
            "md", "markdown" -> markdown(file.readText())
            "csv" -> table(file.readLines().map { splitCsv(it, ',') })
            "tsv" -> table(file.readLines().map { it.split('\t') })
            "docx" -> docx(file)
            "pptx" -> pptx(file)
            "xlsx" -> xlsx(file)
            "odt" -> odt(file)
            "epub" -> epub(file)
            in texts -> "<pre>" + esc(file.readText()) + "</pre>"
            else -> return null
        }
        return page(body, dark)
    }

    fun page(body: String, dark: Boolean): String {
        val bg = if (dark) "#141414" else "#FAF8F4"
        val fg = if (dark) "#E8E4DC" else "#1C1B1A"
        val line = if (dark) "#333" else "#DDD"
        val card = if (dark) "#1F1F1F" else "#FFFFFF"
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<style>
body{background:$bg;color:$fg;font:18px/1.6 Georgia,serif;margin:0;padding:16px 20px 48px;word-wrap:break-word}
h1,h2,h3,h4{font-family:sans-serif;line-height:1.25;margin:1.2em 0 .4em}
img{max-width:100%;height:auto;border-radius:6px}
img.solo{display:block;margin:0 auto}
pre{white-space:pre-wrap;font:14px/1.5 monospace}
table{border-collapse:collapse;font:14px/1.4 sans-serif;display:block;overflow-x:auto;margin:12px 0}
td,th{border:1px solid $line;padding:6px 8px;vertical-align:top}
th{text-align:left}
.slide{background:$card;border:1px solid $line;border-radius:12px;padding:16px 18px;margin:0 0 18px;min-height:40vw}
.slide .n{font:12px sans-serif;opacity:.6}
.sheet{font:600 15px sans-serif;margin-top:20px}
blockquote{border-left:3px solid $line;margin:0;padding-left:12px;opacity:.85}
a{color:#B39DFF}
</style></head><body>$body</body></html>"""
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- Word ----

    private fun docx(file: File): String = ZipFile(file).use { zip ->
        val rels = rels(zip, "word/_rels/document.xml.rels", "word/")
        val xml = zip.getEntry("word/document.xml") ?: return "<p>This Word file is empty.</p>"
        val out = StringBuilder()
        val p = parser(zip.getInputStream(xml).readBytes())
        var para = StringBuilder(); var style = ""; var list = false; var inList = false
        var bold = false; var ital = false; var under = false
        var inTable = 0; var cell = StringBuilder(); var row = StringBuilder(); var tbl = StringBuilder()
        fun target() = if (inTable > 0) cell else out
        fun endPara() {
            val text = para.toString()
            val h = Regex("(?i)heading\\s*([1-6])|^title$").find(style)
            val tag = when { h == null -> "p"; h.groupValues[1].isNotEmpty() -> "h" + h.groupValues[1]; else -> "h1" }
            val t = target()
            if (list && inTable == 0) { if (!inList) { t.append("<ul>"); inList = true }; t.append("<li>").append(text).append("</li>") }
            else {
                if (inList && inTable == 0) { t.append("</ul>"); inList = false }
                if (text.isNotBlank() || tag == "p") t.append("<$tag>").append(text).append("</$tag>")
            }
            para = StringBuilder(); style = ""; list = false
        }
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            val n = p.name
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (n) {
                    "w:tbl" -> { if (inList) { out.append("</ul>"); inList = false }; inTable++; tbl = StringBuilder("<table>") }
                    "w:tr" -> row = StringBuilder("<tr>")
                    "w:tc" -> cell = StringBuilder()
                    "w:pStyle" -> style = p.getAttributeValue(null, "w:val").orEmpty()
                    "w:numPr" -> list = true
                    "w:r" -> { bold = false; ital = false; under = false }
                    "w:b" -> bold = p.getAttributeValue(null, "w:val").let { it == null || it == "1" || it == "true" }
                    "w:i" -> ital = p.getAttributeValue(null, "w:val").let { it == null || it == "1" || it == "true" }
                    "w:u" -> under = p.getAttributeValue(null, "w:val").let { it != null && it != "none" }
                    "w:t" -> {
                        var t = esc(p.nextText())
                        if (bold) t = "<b>$t</b>"; if (ital) t = "<i>$t</i>"; if (under) t = "<u>$t</u>"
                        para.append(t)
                    }
                    "w:tab" -> para.append(" &emsp; ")
                    "w:br", "w:cr" -> para.append("<br>")
                    "a:blip" -> rels[p.getAttributeValue(null, "r:embed")]?.let { para.append(img(zip, it)) }
                    "v:imagedata" -> rels[p.getAttributeValue(null, "r:id")]?.let { para.append(img(zip, it)) }
                }
                XmlPullParser.END_TAG -> when (n) {
                    "w:p" -> endPara()
                    "w:tc" -> row.append("<td>").append(cell).append("</td>")
                    "w:tr" -> tbl.append(row).append("</tr>")
                    "w:tbl" -> { inTable--; out.append(tbl).append("</table>") }
                }
            }
        }
        if (inList) out.append("</ul>")
        out.toString()
    }

    // ---- Slides ----

    private fun pptx(file: File): String = ZipFile(file).use { zip ->
        val presRels = rels(zip, "ppt/_rels/presentation.xml.rels", "ppt/")
        val order = mutableListOf<String>()
        zip.getEntry("ppt/presentation.xml")?.let { e ->
            val p = parser(zip.getInputStream(e).readBytes())
            while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "p:sldId")
                presRels[p.getAttributeValue(null, "r:id")]?.let { order += it }
        }
        if (order.isEmpty()) order += zip.entries().toList().map { it.name }
            .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val out = StringBuilder()
        order.forEachIndexed { i, path ->
            val e = zip.getEntry(path) ?: return@forEachIndexed
            val rels = rels(zip, path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels", path.substringBeforeLast('/') + "/")
            out.append("<div class=slide><div class=n>Slide ${i + 1}</div>")
            val p = parser(zip.getInputStream(e).readBytes())
            var title = false; var para = StringBuilder(); var bold = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "p:sp" -> title = false
                        "p:ph" -> p.getAttributeValue(null, "type").let { if (it == "title" || it == "ctrTitle") title = true }
                        "a:rPr" -> bold = p.getAttributeValue(null, "b") == "1"
                        "a:t" -> esc(p.nextText()).let { para.append(if (bold) "<b>$it</b>" else it) }
                        "a:br" -> para.append("<br>")
                        "a:blip" -> rels[p.getAttributeValue(null, "r:embed")]?.let { out.append(img(zip, it)) }
                    }
                    XmlPullParser.END_TAG -> if (p.name == "a:p") {
                        if (para.isNotBlank()) out.append(if (title) "<h2>$para</h2>" else "<p>$para</p>")
                        para = StringBuilder()
                    }
                }
            }
            // Speaker notes, if the slide has them.
            rels.values.firstOrNull { it.contains("notesSlide") }?.let { np -> zip.getEntry(np) }?.let { ne ->
                val q = parser(zip.getInputStream(ne).readBytes()); val notes = StringBuilder()
                while (q.next() != XmlPullParser.END_DOCUMENT) if (q.eventType == XmlPullParser.START_TAG && q.name == "a:t") notes.append(esc(q.nextText())).append(' ')
                val t = notes.toString().trim().removeSuffix((i + 1).toString()).trim()
                if (t.isNotBlank()) out.append("<blockquote>").append(t).append("</blockquote>")
            }
            out.append("</div>")
        }
        if (out.isEmpty()) "<p>These slides are empty.</p>" else out.toString()
    }

    // ---- Sheets ----

    private fun xlsx(file: File): String = ZipFile(file).use { zip ->
        val shared = mutableListOf<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { e ->
            val p = parser(zip.getInputStream(e).readBytes()); var cur = StringBuilder(); var inSi = false
            while (p.next() != XmlPullParser.END_DOCUMENT) when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> { inSi = true; cur = StringBuilder() }; "t" -> if (inSi) cur.append(p.nextText()) }
                XmlPullParser.END_TAG -> if (p.name == "si") { shared += cur.toString(); inSi = false }
            }
        }
        val wbRels = rels(zip, "xl/_rels/workbook.xml.rels", "xl/")
        val sheets = mutableListOf<Pair<String, String>>()
        zip.getEntry("xl/workbook.xml")?.let { e ->
            val p = parser(zip.getInputStream(e).readBytes())
            while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet")
                wbRels[p.getAttributeValue(null, "r:id")]?.let { sheets += p.getAttributeValue(null, "name").orEmpty() to it }
        }
        val out = StringBuilder()
        for ((name, path) in sheets) {
            val e = zip.getEntry(path) ?: continue
            val rows = mutableListOf<MutableList<String>>()
            val p = parser(zip.getInputStream(e).readBytes()); var type = ""; var col = 0; var value = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> rows += mutableListOf<String>()
                    "c" -> { type = p.getAttributeValue(null, "t").orEmpty(); col = colIndex(p.getAttributeValue(null, "r").orEmpty()); value = "" }
                    "v" -> value = p.nextText()
                    "t" -> value += p.nextText()
                }
                XmlPullParser.END_TAG -> if (p.name == "c" && rows.isNotEmpty()) {
                    val text = if (type == "s") shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else value
                    val r = rows.last(); while (r.size < col) r += ""; r += text
                }
            }
            if (sheets.size > 1) out.append("<div class=sheet>").append(esc(name)).append("</div>")
            out.append(table(rows))
        }
        if (out.isEmpty()) "<p>This sheet is empty.</p>" else out.toString()
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return (n - 1).coerceAtLeast(0)
    }

    // ---- OpenDocument text and EPUB ----

    private fun odt(file: File): String = ZipFile(file).use { zip ->
        val e = zip.getEntry("content.xml") ?: return "<p>This file is empty.</p>"
        val p = parser(zip.getInputStream(e).readBytes()); val out = StringBuilder(); var para = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) when (p.eventType) {
            XmlPullParser.TEXT -> para.append(esc(p.text))
            XmlPullParser.START_TAG -> when (p.name) { "text:line-break" -> para.append("<br>"); "text:tab" -> para.append(" &emsp; ") }
            XmlPullParser.END_TAG -> when (p.name) {
                "text:p" -> { out.append("<p>").append(para).append("</p>"); para = StringBuilder() }
                "text:h" -> { out.append("<h2>").append(para).append("</h2>"); para = StringBuilder() }
            }
        }
        out.toString()
    }

    private fun epub(file: File): String = ZipFile(file).use { zip ->
        val pages = zip.entries().toList().map { it.name }.filter { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }.sorted()
        pages.joinToString("<hr>") { name ->
            val html = zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
            html.substringAfter("<body", html).substringAfter('>').substringBefore("</body>")
                .replace(Regex("(?is)<script.*?</script>"), "").replace(Regex("(?i)<img[^>]*>"), "")
        }
    }

    // ---- Markdown and tables ----

    fun markdown(src: String): String {
        val out = StringBuilder(); var inList = false; var inCode = false
        fun inline(s: String) = esc(s)
            .replace(Regex("!\\[([^]]*)]\\(([^)]+)\\)"), "<img alt=\"$1\" src=\"$2\">")
            .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
            .replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)\\*(?!\\w)"), "<i>$1</i>")
            .replace(Regex("`([^`]+)`"), "<code>$1</code>")
        for (raw in src.lines()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { out.append(if (inCode) "</pre>" else "<pre>"); inCode = !inCode; continue }
            if (inCode) { out.append(esc(line)).append('\n'); continue }
            val item = Regex("^\\s*([-*+]|\\d+\\.)\\s+(.*)").find(line)
            if (item != null) { if (!inList) { out.append("<ul>"); inList = true }; out.append("<li>").append(inline(item.groupValues[2])).append("</li>"); continue }
            if (inList) { out.append("</ul>"); inList = false }
            val h = Regex("^(#{1,6})\\s+(.*)").find(line)
            when {
                h != null -> out.append("<h${h.groupValues[1].length}>").append(inline(h.groupValues[2])).append("</h${h.groupValues[1].length}>")
                line.startsWith(">") -> out.append("<blockquote>").append(inline(line.removePrefix(">").trim())).append("</blockquote>")
                line.isBlank() -> {}
                else -> out.append("<p>").append(inline(line)).append("</p>")
            }
        }
        if (inList) out.append("</ul>"); if (inCode) out.append("</pre>")
        return out.toString()
    }

    private fun table(rows: List<List<String>>): String {
        if (rows.isEmpty()) return ""
        val sb = StringBuilder("<table>")
        rows.forEachIndexed { i, r ->
            sb.append("<tr>"); r.forEach { c -> sb.append(if (i == 0) "<th>" else "<td>").append(esc(c)).append(if (i == 0) "</th>" else "</td>") }; sb.append("</tr>")
        }
        return sb.append("</table>").toString()
    }

    private fun splitCsv(line: String, sep: Char): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> q = !q
                c == sep && !q -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    // ---- Zip helpers ----

    private fun parser(bytes: ByteArray): XmlPullParser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(bytes.inputStream(), "UTF-8")
    }

    /** Relationship id to the full path in the zip. */
    private fun rels(zip: ZipFile, path: String, base: String): Map<String, String> {
        val e = zip.getEntry(path) ?: return emptyMap()
        val p = parser(zip.getInputStream(e).readBytes()); val out = HashMap<String, String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
            val t = p.getAttributeValue(null, "Target") ?: continue
            if (p.getAttributeValue(null, "TargetMode") == "External") continue
            out[p.getAttributeValue(null, "Id")] = normalize(if (t.startsWith("/")) t.removePrefix("/") else base + t)
        }
        return out
    }

    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (s in path.split('/')) when (s) { "", "." -> {}; ".." -> parts.removeLastOrNull(); else -> parts.addLast(s) }
        return parts.joinToString("/")
    }

    private fun img(zip: ZipFile, path: String): String {
        val e = zip.getEntry(path) ?: return ""
        if (e.size > 8_000_000) return ""
        val ext = path.substringAfterLast('.').lowercase()
        val mime = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; "bmp" -> "image/bmp"; "png" -> "image/png"; else -> return "" }
        val b64 = Base64.encodeToString(zip.getInputStream(e).readBytes(), Base64.NO_WRAP)
        return "<img src=\"data:$mime;base64,$b64\">"
    }
}
