package app.obsilearn.data

import app.obsi.core.data.Transcripts
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.obsi.core.data.AppSettings

/**
 * Course progress as one Markdown note per course, in your vault. Your vault sync (Obsidian Sync,
 * Syncthing, or any other) carries it to your other phones and to a desktop app.
 *
 * Each lesson is a checkbox line. Tick it in Obsidian and the app counts the lesson as done.
 * A hidden comment on each line keeps the place in the lesson and when it was saved, so two
 * phones agree: done is kept from either side, and the newer place wins.
 * Anything you write under the end line is yours and stays.
 */
object ProgressNotes {
    const val END = "%% Write your own notes below this line. ObsiLearn keeps them. %%"

    data class Mark(val done: Boolean, val pos: Double, val at: Long)

    private val line = Regex("""^\s*- \[( |x|X)\] .*?<!-- ol:(\w+) pos=(\d+(?:\.\d+)?) at=(\d+) -->\s*$""")

    /** Reads the marks out of a note. Lines you added by hand without a mark are skipped. */
    fun parse(text: String): Map<String, Mark> {
        val out = HashMap<String, Mark>()
        for (l in text.lineSequence()) {
            if (l.trim() == END) break
            val m = line.find(l) ?: continue
            out[m.groupValues[2]] = Mark(m.groupValues[1] != " ", m.groupValues[3].toDouble(), m.groupValues[4].toLong())
        }
        return out
    }

    private val roundLine = Regex("""(?m)^round: (\d+)\s*$""")

    /** The round the note is on. 1 for a note from before rounds. */
    fun round(text: String): Int = roundLine.find(text.substringBefore("\n---\n\n", text))?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /** What you wrote under the end line. */
    fun tail(text: String): String {
        val i = text.indexOf(END)
        return if (i < 0) "" else text.substring(i + END.length).trimStart('\n', '\r')
    }

    fun fileName(course: Course): String = course.title.replace(Regex("""[\\/:*?"<>|#^\[\]]"""), " ").replace(Regex("\\s+"), " ").trim().ifBlank { "Course" }

    /** The whole note. [videos] are the lessons' saved state, by id. */
    fun render(course: Course, videos: Map<String, SavedVideo>, tail: String, now: Long, rounds: CourseRounds = CourseRounds()): String = buildString {
        val all = course.lessons
        val done = all.count { videos[it.id]?.done == true }
        val pct = if (all.isEmpty()) 0 else done * 100 / all.size
        append("---\n")
        // These keys let you find the notes, or leave them out: -tag:#obsilearn in search, or type in Bases.
        append("type: obsilearn-progress\n")
        append("obsilearn: ").append(course.id).append('\n')
        append("course: \"").append(course.title.replace("\"", "'")).append("\"\n")
        append("lessons: ").append(all.size).append('\n')
        append("done: ").append(done).append('\n')
        append("progress: ").append(pct).append('\n')
        append("round: ").append(rounds.current).append('\n')
        append("updated: ").append(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0)).append('\n')
        append("tags:\n  - obsilearn/progress\n")
        append("---\n\n")
        append("# ").append(course.title).append("\n\n")
        if (rounds.current > 1) append("Round ").append(rounds.current).append(": ")
        append("**").append(done).append(" of ").append(all.size).append(" lessons done** (").append(pct).append("%)\n")
        if (rounds.past.isNotEmpty()) append('\n')
        for (r in rounds.past.reversed()) append("- Round ").append(r.n).append(": ").append(r.done).append(" of ").append(r.total)
            .append(" done, ").append(day(r.started)).append(" to ").append(day(r.finished)).append('\n')
        for (m in course.modules) {
            if (m.lessons.isEmpty()) continue
            append("\n## ").append(m.title).append("\n\n")
            for (l in m.lessons) {
                val v = videos[l.id]
                val pos = v?.positionSec ?: 0.0
                append("- [").append(if (v?.done == true) "x" else " ").append("] ")
                append("[").append(l.title.replace("[", "(").replace("]", ")")).append("](obsilearn://lesson/").append(l.id)
                if (pos >= 5 && v?.done != true) append("?t=").append(pos.toInt())
                append(")")
                if (pos >= 5 && v?.done != true) append(" · ").append(Transcripts.formatTime(pos))
                val n = v?.highlights?.size ?: 0
                if (n > 0) append(" · ").append(n).append(if (n == 1) " highlight" else " highlights")
                val w = rounds.times(l.id)
                if (w > 1) append(" · watched ").append(w).append(" times")
                append(" <!-- ol:").append(l.id).append(" pos=").append(pos.toInt()).append(" at=").append(v?.playedAt ?: 0L).append(" -->\n")
            }
        }
        append("\n").append(END).append("\n")
        if (tail.isNotBlank()) append("\n").append(tail.trimEnd()).append("\n")
    }

    /**
     * What to change in the library after reading a note: a lesson ticked there is done here, and a place
     * saved later there is the place here. Returns lesson id to (done, position, played at), only for changes.
     */
    fun merge(marks: Map<String, Mark>, videos: Map<String, SavedVideo>): Map<String, Mark> {
        val out = HashMap<String, Mark>()
        for ((id, m) in marks) {
            val v = videos[id] ?: continue
            val done = m.done && !v.done
            val newer = m.at > v.playedAt && kotlin.math.abs(m.pos - v.positionSec) >= 1
            if (done || newer) out[id] = Mark(m.done || v.done, if (newer) m.pos else v.positionSec, if (newer) m.at else v.playedAt)
        }
        return out
    }

    // ---------- the vault ----------

    fun read(context: Context, s: AppSettings, folder: String, course: Course): String? = runCatching {
        val dir = app.obsi.core.obsidian.VaultNotes.folder(context, s, folder, false) ?: return null
        val f = dir.findFile(fileName(course) + ".md") ?: return null
        context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    /** Writes the note, keeping what you wrote under the end line. False when there is no vault folder. */
    private fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    fun write(context: Context, s: AppSettings, folder: String, course: Course, videos: Map<String, SavedVideo>, rounds: CourseRounds = CourseRounds()): Boolean = runCatching {
        val dir = app.obsi.core.obsidian.VaultNotes.folder(context, s, folder, true) ?: return false
        val name = fileName(course) + ".md"
        val existing = dir.findFile(name)
        val old = existing?.let { f -> context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() } }.orEmpty()
        val text = render(course, videos, tail(old), System.currentTimeMillis(), rounds)
        // The same lessons, the same marks: no write, so the vault sync has nothing to send.
        if (old.isNotEmpty() && old.substringAfter("\n---\n") == text.substringAfter("\n---\n")) return true
        val f = existing ?: dir.createFile("application/octet-stream", name) ?: return false
        context.contentResolver.openOutputStream(f.uri, "wt")!!.bufferedWriter().use { it.write(text) }
        true
    }.getOrDefault(false)
}
