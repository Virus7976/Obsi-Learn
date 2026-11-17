package app.obsilearn.data

import app.obsi.core.data.TranscriptData
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The words of every transcript and every file you opened, so search finds a phrase inside them.
 * A file's text is kept when it is first shown (or when you ask to make a course's files searchable).
 * Transcripts are read from the library. All of it is read once, then kept in memory.
 */
class TextIndex(
    private val dir: File,
    private val transcript: (String) -> TranscriptData?,
    /** When a transcript was last written, so a new one is read again. */
    private val stamp: (String) -> Long = { 0L },
) {
    /** One item's text, with where each piece starts: a second for a transcript, a word for a file. */
    class Text(val body: String, val starts: IntArray, val places: DoubleArray)

    class Found(val id: String, val snippet: String, val at: Double, val matchStart: Int, val matchLen: Int)

    private val cache = ConcurrentHashMap<String, Text>()
    private val stamps = ConcurrentHashMap<String, Long>()
    @Volatile private var loadedFor: Set<String> = emptySet()

    private fun file(id: String) = File(dir.also { it.mkdirs() }, "$id.txt")

    fun has(id: String) = file(id).exists()

    /** Keeps a file's text. [words] is the text as the reader numbers it, one entry per word. */
    fun putFile(id: String, words: List<String>) {
        file(id).writeText(words.joinToString(" "))
        cache.remove(id)
    }

    /** Forget an item, after its transcript changed. */
    fun drop(id: String) = cache.remove(id)

    private fun load(id: String, isFile: Boolean): Text? {
        val st = if (isFile) file(id).lastModified() else stamp(id)
        if (stamps[id] != st) { cache.remove(id); stamps[id] = st }
        cache[id]?.let { return it }
        val t = if (isFile) {
            val body = file(id).takeIf { it.exists() }?.readText() ?: return null
            // A word's number is how many spaces come before it.
            val starts = ArrayList<Int>(); val places = ArrayList<Double>()
            var w = 0
            starts += 0; places += 0.0
            body.forEachIndexed { i, c -> if (c == ' ') { w++; if (w % 20 == 0) { starts += i + 1; places += w.toDouble() } } }
            Text(body, starts.toIntArray(), places.toDoubleArray())
        } else {
            val cues = transcript(id)?.cues?.takeIf { it.isNotEmpty() } ?: return null
            val sb = StringBuilder(); val starts = IntArray(cues.size); val places = DoubleArray(cues.size)
            cues.forEachIndexed { i, c -> starts[i] = sb.length; places[i] = c.start; sb.append(c.text.trim()).append(' ') }
            Text(sb.toString(), starts, places)
        }
        cache[id] = t
        return t
    }

    /**
     * Finds [query] (all its words, next to each other) in the given items. Each item gives at most
     * [perItem] places. Case and accents do not matter.
     */
    fun search(query: String, items: List<SavedVideo>, perItem: Int = 3, limit: Int = 60): List<Found> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < 3) return emptyList()
        val out = ArrayList<Found>()
        for (v in items) {
            val t = load(v.meta.id, v.meta.isFile) ?: continue
            var from = 0
            var n = 0
            while (n < perItem) {
                val i = t.body.indexOf(q, from, ignoreCase = true)
                if (i < 0) break
                // The place: the last start at or before the match.
                var k = java.util.Arrays.binarySearch(t.starts, i)
                if (k < 0) k = -k - 2
                // A file's place is its word: the spaces before the match. A transcript's is its line's time.
                val at = if (v.meta.isFile) t.body.substring(0, i).count { it == ' ' }.toDouble() else t.places.getOrElse(k.coerceAtLeast(0)) { 0.0 }
                val a = (i - 50).coerceAtLeast(0).let { s -> t.body.lastIndexOf(' ', s).let { if (it < 0 || i - it > 70) s else it + 1 } }
                val b = (i + q.length + 70).coerceAtMost(t.body.length)
                val snippet = (if (a > 0) "…" else "") + t.body.substring(a, b).trim() + (if (b < t.body.length) "…" else "")
                out += Found(v.meta.id, snippet, at, i - a + (if (a > 0) 1 else 0), q.length)
                from = i + q.length
                n++
                if (out.size >= limit) return out
            }
        }
        return out
    }
}
