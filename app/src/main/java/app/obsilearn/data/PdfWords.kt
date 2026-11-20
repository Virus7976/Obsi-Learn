package app.obsilearn.data

/**
 * Where each word of a PDF's text view is on its page. The text view and the pages show the same
 * words, so a highlight made on a page is saved by the same word numbers as one made in the text, and
 * shows in both.
 *
 * The text view is not the page text as it is: it has "Page 2" headings, and a word broken with a hyphen
 * at a line end is joined. So the words are matched in order, a few words of slack at a time.
 */
class PdfWords(private val spots: Array<Spot?>) {
    /** A word's place on its page: the page, and its first and last-plus-one letter in the page's text. */
    data class Spot(val page: Int, val start: Int, val end: Int)

    val size: Int get() = spots.size
    fun spot(word: Int): Spot? = spots.getOrNull(word)

    /** The words whose letters are in [start, end) on [page]. Null when none are. */
    fun words(page: Int, start: Int, end: Int): IntRange? {
        var lo = -1; var hi = -1
        for (i in spots.indices) {
            val s = spots[i] ?: continue
            if (s.page != page || s.end <= start || s.start >= end) continue
            if (lo < 0) lo = i
            hi = i
        }
        return if (lo < 0) null else lo..hi
    }

    /** The letters on [page] that words [w0]..[w1] cover, first to last. Null when none of them is on it. */
    fun letters(page: Int, w0: Int, w1: Int): IntRange? {
        var a = Int.MAX_VALUE; var b = -1
        for (i in w0.coerceAtLeast(0)..w1.coerceAtMost(spots.lastIndex)) {
            val s = spots[i] ?: continue
            if (s.page != page) continue
            if (s.start < a) a = s.start
            if (s.end > b) b = s.end
        }
        return if (b < 0) null else a until b
    }

    /** The pages that words [w0]..[w1] are on. */
    fun pages(w0: Int, w1: Int): Set<Int> =
        (w0.coerceAtLeast(0)..w1.coerceAtMost(spots.lastIndex)).mapNotNull { spots[it]?.page }.toSet()

    companion object {
        private val token = Regex("""\S+""")
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** [words] are the text view's words, in order; [pages] each page's text, as the PDF gives it. */
        fun match(words: List<String>, pages: List<String>): PdfWords {
            val want = words.map(::norm)
            val spots = arrayOfNulls<Spot>(want.size)
            var next = 0
            pages.forEachIndexed { p, text ->
                val toks = token.findAll(text).toList()
                var k = 0
                while (k < toks.size) {
                    val t = toks[k]
                    val n = norm(t.value)
                    if (n.isEmpty()) { k++; continue }
                    val joined = toks.getOrNull(k + 1)?.takeIf { t.value.endsWith('-') }?.let { n + norm(it.value) }
                    var hit = -1
                    var span = 1
                    val stop = minOf(next + 12, want.size)
                    for (j in next until stop) {
                        if (want[j] == n) { hit = j; break }
                        if (joined != null && want[j] == joined) { hit = j; span = 2; break }
                    }
                    if (hit >= 0) {
                        spots[hit] = Spot(p, t.range.first, toks[k + span - 1].range.last + 1)
                        next = hit + 1
                    }
                    k += span
                }
            }
            return PdfWords(spots)
        }
    }
}
