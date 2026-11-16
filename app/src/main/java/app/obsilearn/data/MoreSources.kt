package app.obsilearn.data

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** A plain web page that lists files, as many servers show a folder (Apache, nginx, Caddy, and others). */
object WebFolder {
    private val href = Regex("""href\s*=\s*["']([^"'#?]+)["']""", RegexOption.IGNORE_CASE)

    fun list(http: OkHttpClient, s: Source): List<Entry> {
        val root = s.uri.trim().trimEnd('/') + "/"
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        fun walk(url: String, path: List<String>, depth: Int) {
            if (depth > 6 || !seen.add(url)) return
            val html = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw SourceException("The page said ${r.code}.")
                r.body!!.string()
            }
            for (m in href.findAll(html)) {
                val link = m.groupValues[1]
                if (link.startsWith("..") || link == "/" || link.startsWith("mailto:")) continue
                val full = runCatching { java.net.URI(url).resolve(link).toString() }.getOrNull() ?: continue
                // Only what is under the folder you gave: no links to the parent, other folders or other sites.
                if (!full.startsWith(root) || full == url) continue
                val name = Uri.decode(full.removeSuffix("/").substringAfterLast('/'))
                if (name.isBlank() || name.startsWith(".")) continue
                if (full.endsWith("/")) walk(full, path + name, depth + 1)
                else if (name.contains('.')) out += Entry(path + name, full)
            }
        }
        walk(root, emptyList(), 0)
        return out.distinctBy { it.uri }
    }
}

/** A podcast or any RSS or Atom feed with audio or video files. Each item is a lesson, oldest first. */
object Feed {
    fun course(http: OkHttpClient, s: Source): Course {
        val xml = http.newCall(Request.Builder().url(s.uri.trim()).header("User-Agent", "ObsiLearn").build()).execute().use { r ->
            if (!r.isSuccessful) throw SourceException("The feed said ${r.code}.")
            r.body!!.string()
        }
        val parsed = parse(xml)
        val lessons = parsed.items.reversed().map { it ->
            Lesson(
                id = Layout.id(s.id, it.url), title = it.title.ifBlank { Uri.parse(it.url).lastPathSegment.orEmpty() },
                media = it.url, kind = if (it.type.startsWith("video")) "video" else "audio", size = it.size, file = it.url.substringAfterLast('/'),
            )
        }
        return Course(Layout.id(s.id, ""), s.id, parsed.title.ifBlank { s.name }, listOf(Module(Layout.id(s.id, "m"), "Episodes", lessons)), parsed.image, System.currentTimeMillis())
    }

    data class Item(val title: String, val url: String, val type: String, val size: Long)
    data class Parsed(val title: String, val image: String, val items: List<Item>)

    private fun tag(block: String, name: String): String =
        Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block)?.groupValues?.get(1)
            ?.replace(Regex("""^\s*<!\[CDATA\[|]]>\s*$"""), "")?.replace("&amp;", "&")?.trim().orEmpty()

    private fun attr(tagText: String, name: String): String =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tagText)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()

    fun parse(xml: String): Parsed {
        val items = Regex("""<(item|entry)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(xml).mapNotNull { m ->
            val b = m.value
            val enc = Regex("""<enclosure\b[^>]*>""", RegexOption.IGNORE_CASE).find(b)?.value
                ?: Regex("""<link\b[^>]*rel\s*=\s*["']enclosure["'][^>]*>""", RegexOption.IGNORE_CASE).find(b)?.value
                ?: return@mapNotNull null
            val url = attr(enc, "url").ifBlank { attr(enc, "href") }
            if (url.isBlank()) return@mapNotNull null
            Item(tag(b, "title"), url, attr(enc, "type"), attr(enc, "length").toLongOrNull() ?: 0L)
        }.toList()
        val head = xml.substringBefore("<item").substringBefore("<entry")
        val image = Regex("""<itunes:image\b[^>]*>""", RegexOption.IGNORE_CASE).find(head)?.value?.let { attr(it, "href") }
            ?: tag(tag(head, "image"), "url")
        return Parsed(tag(head, "title"), image, items)
    }
}

/**
 * Google Drive, after you sign in with Google. ObsiLearn asks only to read your files (drive.readonly).
 * Files are "gdrive:<file id>"; the source's [Source.uri] is the folder id.
 */
object Drive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private const val API = "https://www.googleapis.com/drive/v3/files"
    private val json = Json { ignoreUnknownKeys = true }

    /** A fresh access token, without asking you again. Throws when you must sign in again. */
    fun token(context: Context): String {
        val req = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(SCOPE)))
            .build()
        val r = com.google.android.gms.tasks.Tasks.await(
            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context).authorize(req),
            30, java.util.concurrent.TimeUnit.SECONDS,
        )
        if (r.hasResolution() || r.accessToken == null) throw SourceException("Sign in with Google again: Sources, then Google Drive.")
        return r.accessToken!!
    }

    data class File(val id: String, val name: String, val folder: Boolean, val size: Long)

    /** The files and folders right inside one folder. "root" is My Drive. */
    fun children(http: OkHttpClient, token: String, folderId: String): List<File> {
        val out = ArrayList<File>()
        var page: String? = null
        do {
            val url = Uri.parse(API).buildUpon()
                .appendQueryParameter("q", "'$folderId' in parents and trashed = false")
                .appendQueryParameter("fields", "nextPageToken, files(id, name, mimeType, size)")
                .appendQueryParameter("pageSize", "1000")
                .appendQueryParameter("supportsAllDrives", "true")
                .appendQueryParameter("includeItemsFromAllDrives", "true")
                .apply { page?.let { appendQueryParameter("pageToken", it) } }
                .build().toString()
            val body = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (r.code == 401) throw SourceException("Sign in with Google again: Sources, then Google Drive.")
                if (!r.isSuccessful) throw SourceException("Google Drive said ${r.code}.")
                r.body!!.string()
            }
            val o = json.parseToJsonElement(body).jsonObject
            o["files"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { f ->
                val mime = f["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                out += File(
                    f["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), f["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    mime == "application/vnd.google-apps.folder", f["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            }
            page = o["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return out
    }

    fun list(context: Context, http: OkHttpClient, s: Source): List<Entry> {
        val token = token(context)
        val out = ArrayList<Entry>()
        fun walk(id: String, path: List<String>, depth: Int) {
            if (depth > 8) return
            for (f in children(http, token, id)) {
                if (f.name.startsWith(".")) continue
                if (f.folder) walk(f.id, path + f.name, depth + 1) else out += Entry(path + f.name, "gdrive:" + f.id, f.size)
            }
        }
        walk(s.uri, emptyList(), 0)
        return out
    }

    fun url(uri: String): String = "$API/${uri.removePrefix("gdrive:")}?alt=media&supportsAllDrives=true"
}
