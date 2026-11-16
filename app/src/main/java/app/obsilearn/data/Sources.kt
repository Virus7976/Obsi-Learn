package app.obsilearn.data

import app.obsi.core.data.JsonFileStore
import app.obsi.core.data.storeJson
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

@Serializable
data class CourseData(val sources: List<Source> = emptyList(), val courses: List<Course> = emptyList())

class SourceException(message: String) : Exception(message)

/** Turns what went wrong while reading a source into words a person can act on. */
object SourceErrors {
    /** A WebDAV or SMB source whose address belongs to the other kind, switched to that kind. */
    fun kindFor(s: Source): Source {
        val u = s.uri.trim().lowercase()
        return when {
            s.kind == "webdav" && (u.startsWith("smb://") || u.startsWith("\\\\")) -> s.copy(kind = "smb", id = s.id.replaceFirst("webdav", "smb"))
            s.kind == "smb" && (u.startsWith("http://") || u.startsWith("https://")) -> s.copy(kind = "webdav", id = s.id.replaceFirst("smb", "webdav"))
            else -> s
        }
    }

    /** The http address to try, when an https one failed because the server speaks plain http. */
    fun plainHttpFor(uri: String, e: Throwable?): String? {
        if (e == null || !uri.startsWith("https://", true)) return null
        val ssl = generateSequence(e) { it.cause }.any { it is javax.net.ssl.SSLException }
        val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        val plain = "tls packet header" in text || "plaintext" in text || "unrecognized ssl message" in text || "wrong version number" in text
        // A plain http port often says nothing at all to an https hello, so the read times out.
        val silent = generateSequence(e) { it.cause }.any { it is java.net.SocketTimeoutException }
        return if ((ssl && plain) || silent) "http://" + uri.substring("https://".length) else null
    }

    fun say(e: Throwable): String {
        if (e is SourceException) return e.message ?: "Could not read it."
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            chain.any { it is java.net.UnknownHostException } -> "That address was not found. Check it for typing mistakes."
            chain.any { it is java.net.ConnectException } -> "Nothing answered at that address. Check the address and port, and that the phone is on the same Wi-Fi as the server."
            chain.any { it is java.net.SocketTimeoutException } -> "The server took too long to answer. Check that it is on, and that the phone is on the same Wi-Fi."
            "trust anchor" in text || "certpath" in text || chain.any { it is java.security.cert.CertificateException } ->
                "The server's https certificate is not trusted. Home servers often make their own. Try the same address with http:// instead."
            "cleartext" in text -> "This copy of ObsiLearn cannot use http. Use https, or update the app."
            chain.any { it is javax.net.ssl.SSLException } -> "Could not make a secure (https) link to the server. Try the same address with http:// instead."
            else -> e.message ?: "Could not read it."
        }
    }
}

/** Your sources and the courses found in them. Scanning a source again keeps your progress, by lesson id. */
class CourseStore(private val context: Context) : JsonFileStore<CourseData>(File(context.filesDir, "courses.json")) {
    private val _state = MutableStateFlow(readOrNull() ?: CourseData())
    val state: StateFlow<CourseData> = _state.asStateFlow()

    private val _scanning = MutableStateFlow<Set<String>>(emptySet())
    /** Source ids being read right now. */
    val scanning: StateFlow<Set<String>> = _scanning.asStateFlow()

    override fun encode(value: CourseData) = storeJson.encodeToString(CourseData.serializer(), value)
    override fun decode(text: String) = storeJson.decodeFromString(CourseData.serializer(), text)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val courses: List<Course> get() = _state.value.courses.filter { !it.hidden }.sortedBy { it.order }
    fun source(id: String): Source? = _state.value.sources.firstOrNull { it.id == id }
    fun course(id: String): Course? = _state.value.courses.firstOrNull { it.id == id }

    /** The course and module a lesson is in. */
    fun find(lessonId: String): Triple<Course, Module, Lesson>? {
        for (c in _state.value.courses) for (m in c.modules) for (l in m.lessons) if (l.id == lessonId) return Triple(c, m, l)
        return null
    }

    fun addSource(s: Source) {
        _state.update { it.copy(sources = it.sources.filterNot { o -> o.id == s.id } + s) }
        persist(_state.value)
    }

    fun removeSource(id: String) {
        _state.update { d -> d.copy(sources = d.sources.filterNot { it.id == id }, courses = d.courses.filterNot { it.sourceId == id }) }
        persist(_state.value)
    }

    fun editCourse(id: String, block: (Course) -> Course) {
        _state.update { d -> d.copy(courses = d.courses.map { if (it.id == id) block(it) else it }) }
        persist(_state.value)
    }

    fun editSource(id: String, block: (Source) -> Source) {
        _state.update { d -> d.copy(sources = d.sources.map { if (it.id == id) block(it) else it }) }
        persist(_state.value)
    }

    /** Reads the source again. Courses keep their hidden flag and order. Returns how many courses it found. */
    suspend fun scan(sourceId: String): Int = withContext(Dispatchers.IO) {
        val s = source(sourceId) ?: return@withContext 0
        _scanning.update { it + sourceId }
        try {
            val found = when (s.kind) {
                "hub" -> Hub.courses(this@CourseStore, s)
                "links" -> listOf(linksCourse(s))
                "feed" -> listOf(Feed.course(http, s))
                else -> Layout.courses(s, entries(s))
            }
            _state.update { d ->
                val old = d.courses.associateBy { it.id }
                val kept = d.courses.filter { it.sourceId != sourceId }
                val next = found.mapIndexed { i, c ->
                    old[c.id]?.let { o -> c.copy(hidden = o.hidden, order = o.order) }
                        ?: c.copy(order = (kept.maxOfOrNull { it.order } ?: 0) + 1 + i)
                }
                d.copy(courses = kept + next)
            }
            persist(_state.value)
            found.size
        } finally {
            _scanning.update { it - sourceId }
        }
    }

    suspend fun scanAll() { _state.value.sources.forEach { runCatching { scan(it.id) } } }

    // ---------- listing ----------

    fun entries(s: Source): List<Entry> = when (s.kind) {
        "folder" -> folderEntries(Uri.parse(s.uri))
        "webdav" -> WebDav.list(http, s)
        "smb" -> Smb.list(s)
        "web" -> WebFolder.list(http, s)
        "gdrive" -> Drive.list(context, http, s)
        else -> emptyList()
    }

    private fun folderEntries(tree: Uri): List<Entry> {
        val out = ArrayList<Entry>()
        val cr = context.contentResolver
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        )
        fun walk(docId: String, path: List<String>, depth: Int) {
            if (depth > 8) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, path + name, depth + 1)
                    else out += Entry(path + name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), if (c.isNull(3)) 0L else c.getLong(3))
                }
            } ?: throw SourceException("The phone no longer lets ObsiLearn read that folder. Pick it again.")
        }
        walk(DocumentsContract.getTreeDocumentId(tree), emptyList(), 0)
        return out
    }

    private fun linksCourse(s: Source): Course {
        val lessons = s.uri.lines().map { it.trim() }.filter { it.startsWith("http", true) || it.startsWith("content:") }.map { link ->
            val name = Uri.parse(link).lastPathSegment.orEmpty().ifBlank { link }
            val ext = name.substringAfterLast('.', "").lowercase()
            Lesson(
                id = Layout.id(s.id, link), title = Layout.title(name.substringBeforeLast('.')), media = link,
                kind = if (ext in Layout.audioExt) "audio" else "video", file = name,
            )
        }
        return Course(Layout.id(s.id, ""), s.id, s.name, listOf(Module(Layout.id(s.id, "m"), "Lessons", lessons)), "", System.currentTimeMillis())
    }

    // ---------- reading files ----------

    /** The address the player streams from, and the headers it must send. */
    fun playable(sourceId: String, uri: String): Pair<String, Map<String, String>> {
        val s = source(sourceId) ?: return uri to emptyMap()
        return when (s.kind) {
            "hub" -> Hub.url(s, uri) to emptyMap()
            "webdav" -> uri to (if (s.user.isNotBlank()) mapOf("Authorization" to Credentials.basic(s.user, s.secret)) else emptyMap())
            // A Google token lasts an hour. Each play asks for one, and Google hands back the same one while it is good.
            "gdrive" -> Drive.url(uri) to mapOf("Authorization" to "Bearer " + Drive.token(context))
            else -> uri to emptyMap()
        }
    }

    /** Opens any file of a source to read it. */
    fun open(sourceId: String, uri: String): InputStream {
        if (uri.startsWith("content:") || uri.startsWith("file:")) {
            return context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw SourceException("Could not open the file.")
        }
        if (uri.startsWith("smb:", true)) return Smb.file(source(sourceId) ?: sourceFor(uri) ?: Source("", "smb", "", uri), uri).inputStream
        val (url, headers) = playable(sourceId, uri)
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val r = http.newCall(req).execute()
        if (!r.isSuccessful) { r.close(); throw SourceException("The server said ${r.code}.") }
        return r.body!!.byteStream()
    }

    /** The source a file address belongs to, by the start of the address. For the share reader in the player. */
    fun sourceFor(uri: String): Source? = _state.value.sources.filter { it.kind == "smb" && uri.startsWith(Smb.root(it), true) }.maxByOrNull { it.uri.length }

    fun readText(sourceId: String, uri: String): String = open(sourceId, uri).use { it.readBytes().toString(Charsets.UTF_8) }

    /** The course's JSON from the hub. Shared with [Hub]. */
    internal fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonObject {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw SourceException("Wrong pairing code.")
            if (!r.isSuccessful) throw SourceException("The hub said ${r.code}.")
            return json.parseToJsonElement(r.body!!.string()).jsonObject
        }
    }
}

/** WebDAV, as NAS boxes, Nextcloud and many file servers speak it. Folders are read one level at a time. */
object WebDav {
    private val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

    fun list(http: OkHttpClient, s: Source): List<Entry> {
        val root = s.uri.trimEnd('/') + "/"
        val rootPath = Uri.parse(root).encodedPath.orEmpty()
        val out = ArrayList<Entry>()
        fun walk(url: String, path: List<String>, depth: Int) {
            if (depth > 8) return
            val req = Request.Builder().url(url)
                .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
                .header("Depth", "1")
                .apply { if (s.user.isNotBlank()) header("Authorization", Credentials.basic(s.user, s.secret)) }
                .build()
            val xml = http.newCall(req).execute().use { r ->
                if (r.code == 401) throw SourceException("The server did not take that user name and password.")
                if (!r.isSuccessful) throw SourceException("The server said ${r.code}.")
                r.body!!.string()
            }
            val here = Uri.parse(url).encodedPath.orEmpty().trimEnd('/')
            for (item in parse(xml)) {
                val p = item.href.let { if (it.startsWith("http")) Uri.parse(it).encodedPath.orEmpty() else it }.trimEnd('/')
                if (p == here || !p.startsWith(rootPath.trimEnd('/'))) continue
                val name = Uri.decode(p.substringAfterLast('/'))
                if (name.isBlank() || name.startsWith(".")) continue
                val full = Uri.parse(root).buildUpon().encodedPath(p + if (item.dir) "/" else "").build().toString()
                if (item.dir) walk(full, path + name, depth + 1) else out += Entry(path + name, full, item.size)
            }
        }
        walk(root, emptyList(), 0)
        return out
    }

    data class Item(val href: String, val dir: Boolean, val size: Long)

    /** Reads a PROPFIND answer. Namespace prefixes differ between servers, so only local names are matched. */
    fun parse(xml: String): List<Item> {
        val out = ArrayList<Item>()
        val resp = Regex("""<(?:\w+:)?response\b[^>]*>(.*?)</(?:\w+:)?response>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (m in resp.findAll(xml)) {
            val r = m.groupValues[1]
            val href = Regex("""<(?:\w+:)?href[^>]*>(.*?)</(?:\w+:)?href>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(r)?.groupValues?.get(1)?.trim() ?: continue
            val dir = Regex("""<(?:\w+:)?collection\b""", RegexOption.IGNORE_CASE).containsMatchIn(r)
            val size = Regex("""<(?:\w+:)?getcontentlength[^>]*>(\d+)<""", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            out += Item(href.replace("&amp;", "&"), dir, size)
        }
        return out
    }
}

/**
 * The Imperium desktop hub (API 3). It already sorts its courses, so its manifest is used as it is.
 * Lesson media is stored as "hub:<lessonId>" and turned into a stream address with the pairing code.
 */
object Hub {
    private fun base(s: Source) = s.uri.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    fun url(s: Source, uri: String): String {
        val b = base(s)
        val tok = "token=" + Uri.encode(s.secret)
        return when {
            uri.startsWith("hub:") -> "$b/api/stream?id=${Uri.encode(uri.removePrefix("hub:"))}&$tok"
            uri.startsWith("hubres:") -> "$b/api/resource?id=${Uri.encode(uri.removePrefix("hubres:"))}&$tok"
            uri.startsWith("hubpage:") -> "$b/api/page?id=${Uri.encode(uri.removePrefix("hubpage:"))}&$tok"
            uri.startsWith("hubcover:") -> "$b/api/cover?id=${Uri.encode(uri.removePrefix("hubcover:"))}&$tok"
            else -> uri
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.longOrNull ?: 0L
    private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.booleanOrNull ?: false

    fun courses(store: CourseStore, s: Source): List<Course> {
        val b = base(s)
        val tok = "token=" + Uri.encode(s.secret)
        val lib = store.getJson("$b/api/library?$tok")
        return lib["courses"]?.jsonArray.orEmpty().map { it.jsonObject }.map { c ->
            val cid = c.str("id").orEmpty()
            val full = store.getJson("$b/api/course?id=${Uri.encode(cid)}&$tok")
            val modules = full["modules"]?.jsonArray.orEmpty().map { it.jsonObject }.map { m ->
                Module(
                    id = Layout.id(s.id, cid, m.str("id").orEmpty()),
                    title = m.str("title").orEmpty(),
                    lessons = m["lessons"]?.jsonArray.orEmpty().map { it.jsonObject }.map { l ->
                        val lid = l.str("id").orEmpty()
                        val hasVideo = l["video"] != null && l["video"].toString() != "null"
                        Lesson(
                            id = Layout.id(s.id, lid),
                            title = l.str("title").orEmpty(),
                            media = if (hasVideo) "hub:$lid" else "",
                            kind = if (hasVideo) "video" else "doc",
                            page = if (l["page"] != null && l["page"].toString() != "null") "hubpage:$lid" else "",
                            resources = l["resources"]?.jsonArray.orEmpty().map { it.jsonObject }.map { r ->
                                Resource(r.str("name").orEmpty(), "hubres:" + r.str("id").orEmpty(), r.str("ext").orEmpty(), r.num("size"))
                            },
                            size = l.num("sizeBytes"),
                            file = lid,
                        )
                    },
                )
            }
            Course(Layout.id(s.id, cid), s.id, c.str("title").orEmpty(), modules, if (c.bool("hasCover")) "hubcover:$cid" else "", System.currentTimeMillis())
        }
    }
}
