package app.obsilearn.data

import kotlinx.serialization.Serializable

/**
 * Where courses live. [kind] is one of:
 * - "folder": a folder picked on the phone (also an SD card, a USB stick, or a cloud app that shows up in the file picker).
 *   [uri] is the tree uri.
 * - "webdav": a WebDAV server, like a NAS or Nextcloud. [uri] is the folder address, [user] and [secret] the login.
 * - "hub": the Imperium desktop hub on a PC. [uri] is its address, [secret] the pairing code.
 * - "links": video links you pasted. Each one is a lesson.
 */
@Serializable
data class Source(
    val id: String,
    val kind: String,
    val name: String,
    val uri: String,
    val user: String = "",
    val secret: String = "",
    val addedAt: Long = 0L,
    /** False: every folder with modules in it is its own course. True: the whole source is one course. */
    val oneCourse: Boolean = false,
)

@Serializable
data class Resource(
    val title: String,
    /** Where the file is. A content uri, a web address, or "hub:<id>". */
    val uri: String,
    val ext: String,
    val size: Long = 0L,
)

@Serializable
data class Lesson(
    /** Stable: made from the source and the file's path, so a rescan keeps progress and highlights. */
    val id: String,
    val title: String,
    /** The video or audio to play. Blank for a lesson that is only a page or a document. */
    val media: String = "",
    /** "video", "audio", or "doc" when there is nothing to play. */
    val kind: String = "video",
    /** A subtitle file next to the video (.srt or .vtt). Blank when there is none. */
    val subs: String = "",
    /** The lesson's own web page, if the course came with one (.html). */
    val page: String = "",
    val resources: List<Resource> = emptyList(),
    val size: Long = 0L,
    /** The file name, so a person can find it. */
    val file: String = "",
)

@Serializable
data class Module(
    val id: String,
    val title: String,
    val lessons: List<Lesson> = emptyList(),
    /** Files in the module that belong to no one lesson. */
    val resources: List<Resource> = emptyList(),
)

@Serializable
data class Course(
    val id: String,
    val sourceId: String,
    val title: String,
    val modules: List<Module> = emptyList(),
    /** A picture for the course card. Blank when the course has none. */
    val cover: String = "",
    val scannedAt: Long = 0L,
    /** Courses you take off the list stay hidden on the next scan. */
    val hidden: Boolean = false,
    /** Your own order on the courses page. Lower comes first. */
    val order: Int = 0,
) {
    val lessons: List<Lesson> get() = modules.flatMap { it.lessons }
    val size: Long get() = lessons.sumOf { it.size }
}

/** One file found in a source. [path] is the folders from the source root down, then the file name last. */
data class Entry(val path: List<String>, val uri: String, val size: Long = 0L) {
    val name: String get() = path.last()
    val folders: List<String> get() = path.dropLast(1)
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val base: String get() = name.substringBeforeLast('.')
}

/**
 * Turns a pile of files into courses, modules and lessons. It works for most ways people keep courses:
 * - one folder of videos (one course, one module),
 * - a course folder with a folder per module,
 * - a folder of courses, each with module folders, deeper folders too,
 * - files that share a number ("03. Intro.mp4", "03. Intro.pdf", "03. (PDF) Notes.pdf") are one lesson,
 * - a subtitle file with the same name as a video is that video's transcript.
 */
object Layout {
    val videoExt = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "3gp", "ts", "m2ts", "mpg", "mpeg", "m3u8")
    val audioExt = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "m4b")
    val subsExt = setOf("srt", "vtt")
    val pageExt = setOf("html", "htm")
    val docExt = setOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "csv", "txt", "md", "rtf", "epub", "key", "pages", "numbers", "odt", "odp", "ods", "zip")
    val imageExt = setOf("jpg", "jpeg", "png", "webp")
    private val coverNames = setOf("cover", "folder", "poster", "thumbnail", "thumb", "course")

    fun isMedia(e: Entry) = e.ext in videoExt || e.ext in audioExt

    private val leadNum = Regex("""^\s*(\d{1,4})(?:[.\-_)\s]|$)""")

    /** The lesson number at the start of a name, like 3 in "03. Intro" or "3 - Intro". */
    fun number(name: String): Int? = leadNum.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** A readable title from a file or folder name. */
    fun title(name: String): String {
        var s = name.replace('_', ' ')
        s = s.replace(Regex("""^\s*\d{1,4}\s*[.\-)]*\s*"""), "")
        s = s.replace(Regex("""^\s*\d+(\.\d+)+\.?\s*"""), "") // "1.3. Title"
        s = s.replace(Regex("""\((?:PDF|DOC|DOCX|PPT|PPTX|XLSX|VIDEO|AUDIO)\)\s*""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\s*·\s*[^·]+$"""), "") // "Title · Site name" from saved web pages
        s = s.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '—', ' ')
        return s.ifBlank { name.trim() }
    }

    /** Sorts "2" before "10", the way a person counts. */
    val natural: Comparator<String> = Comparator { a, b ->
        val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val n = x.trimStart('0').length.compareTo(y.trimStart('0').length)
                if (n != 0) n else x.trimStart('0').compareTo(y.trimStart('0'))
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size.compareTo(rb.size)
    }

    /** A short stable id. */
    fun id(vararg parts: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(parts.joinToString("\u0000").toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * Finds the courses in one source. [name] is the source's name, used when the files sit right in the
     * root and so have no folder of their own.
     */
    fun courses(source: Source, files: List<Entry>, now: Long = System.currentTimeMillis()): List<Course> {
        val media = files.filter { isMedia(it) || it.ext in pageExt || it.ext in docExt }
        if (media.isEmpty()) return emptyList()
        val depths = files.filter { isMedia(it) }.map { it.folders.size }
        // Media two or more folders down: the top folders are courses, and the ones under them are modules.
        val library = !source.oneCourse && depths.isNotEmpty() && depths.min() >= 2
        if (!library) return listOf(course(source, source.name, emptyList(), files, now))
        return files.groupBy { it.folders.first() }
            .filter { (_, list) -> list.any { isMedia(it) } }
            .map { (top, list) -> course(source, title(top), listOf(top), list.map { it.copy(path = it.path.drop(1)) }, now) }
            .sortedWith(compareBy(natural) { it.title })
    }

    private fun course(source: Source, name: String, root: List<String>, files: List<Entry>, now: Long): Course {
        val courseId = id(source.id, root.joinToString("/"))
        val cover = files.filter { it.ext in imageExt && it.folders.size <= 1 }
            .sortedBy { if (it.base.lowercase() in coverNames) 0 else 1 }
            .firstOrNull { it.base.lowercase() in coverNames || it.folders.isEmpty() }?.uri.orEmpty()
        val modules = files.groupBy { it.folders }
            .toSortedMap { a, b -> natural.compare(a.joinToString("/"), b.joinToString("/")) }
            .mapNotNull { (folders, list) ->
                val lessons = lessons(source, root + folders, list)
                val loose = list.filter { (it.ext in docExt) && lessons.none { l -> l.resources.any { r -> r.uri == it.uri } || l.media == it.uri } }
                    .map { Resource(title(it.base), it.uri, it.ext, it.size) }
                if (lessons.isEmpty() && loose.isEmpty()) null
                else Module(
                    id = id(courseId, folders.joinToString("/")),
                    title = if (folders.isEmpty()) "Lessons" else folders.joinToString(" / ") { title(it) },
                    lessons = lessons,
                    resources = loose,
                )
            }
        return Course(courseId, source.id, title(name), modules, cover, now)
    }

    /** Groups one folder's files into lessons. */
    private fun lessons(source: Source, at: List<String>, files: List<Entry>): List<Lesson> {
        val media = files.filter { isMedia(it) }.sortedWith(compareBy(natural) { it.name })
        val subs = files.filter { it.ext in subsExt }
        val pages = files.filter { it.ext in pageExt }
        val docs = files.filter { it.ext in docExt }
        val used = HashSet<String>()

        // Lessons by number, when most media files have one and no two share it.
        val nums = media.mapNotNull { number(it.name) }
        val byNumber = media.isNotEmpty() && nums.size == media.size && nums.toSet().size == nums.size

        fun near(e: Entry, list: List<Entry>): List<Entry> = list.filter { f ->
            f.uri !in used && (f.base.equals(e.base, true) || f.base.startsWith(e.base + ".", true) ||
                (byNumber && number(f.name) != null && number(f.name) == number(e.name)))
        }

        val out = ArrayList<Lesson>()
        for (m in media) {
            val sub = near(m, subs).sortedBy { if (it.base.equals(m.base, true)) 0 else 1 }.firstOrNull()
            val page = near(m, pages).firstOrNull()
            val res = near(m, docs)
            listOfNotNull(sub, page).forEach { used += it.uri }
            res.forEach { used += it.uri }
            used += m.uri
            out += Lesson(
                id = id(source.id, (at + m.name).joinToString("/")),
                title = title(m.base),
                media = m.uri,
                kind = if (m.ext in audioExt) "audio" else "video",
                subs = sub?.uri.orEmpty(),
                page = page?.uri.orEmpty(),
                resources = res.map { Resource(title(it.base), it.uri, it.ext, it.size) },
                size = m.size,
                file = m.name,
            )
        }
        // A numbered page or document with no video is a reading lesson of its own.
        if (byNumber || media.isEmpty()) {
            val reading = (pages + docs).filter { it.uri !in used && (number(it.name) != null || media.isEmpty() && it.ext in pageExt) }
                .groupBy { number(it.name) ?: it.base.hashCode() }
            for ((_, group) in reading) {
                val main = group.firstOrNull { it.ext in pageExt } ?: group.first()
                group.forEach { used += it.uri }
                out += Lesson(
                    id = id(source.id, (at + main.name).joinToString("/")),
                    title = title(main.base),
                    kind = "doc",
                    page = if (main.ext in pageExt) main.uri else "",
                    resources = group.filter { it.ext in docExt }.map { Resource(title(it.base), it.uri, it.ext, it.size) },
                    file = main.name,
                )
            }
        }
        return out.sortedWith { a, b -> natural.compare(a.file, b.file) }
    }
}
