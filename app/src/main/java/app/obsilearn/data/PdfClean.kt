package app.obsilearn.data

/**
 * Finds the text a PDF prints on page after page: a copyright notice, the title at the top,
 * "Page 3 of 20" at the bottom. In the text view and in Listen it only gets in the way.
 */
object PdfClean {
    private val digits = Regex("\\d+")
    private val space = Regex("\\s+")

    /** A line as it is compared: small letters, every number the same, one space between words. */
    fun key(line: String): String = space.replace(digits.replace(line.lowercase(), "#"), " ").trim()

    /**
     * The lines (by [key]) found on at least 3 pages and on at least 30% of them. A PDF of 1 or 2 pages
     * has none. Long lines are never counted: a whole paragraph that repeats is real text.
     */
    fun repeated(pages: List<List<String>>): Set<String> {
        if (pages.size < 3) return emptySet()
        val need = maxOf(3, (pages.size * 0.3).toInt())
        val count = HashMap<String, Int>()
        pages.forEach { p ->
            p.map { key(it) }.filter { it.isNotEmpty() && it.length <= 200 }.toSet().forEach { k -> count[k] = (count[k] ?: 0) + 1 }
        }
        return count.filterValues { it >= need }.keys
    }
}
