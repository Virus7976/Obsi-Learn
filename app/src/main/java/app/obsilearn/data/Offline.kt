package app.obsilearn.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Lessons kept on the phone, so they play with no network and with the source gone (a USB stick
 * pulled out, the PC off). Each lesson is kept as the full video, or as audio only, which is far smaller.
 */
class Offline(private val context: Context, private val store: CourseStore) {
    private val dir get() = File(context.filesDir, "offline").also { it.mkdirs() }

    /** A lesson's PDFs, pages and documents, saved with it. The file viewer looks here first. */
    val filesDir: File get() = File(context.filesDir, "offline-files").also { it.mkdirs() }

    /** Gets a lesson's file as a local copy. Set by the app: the same copy the file viewer uses. */
    var fetchFile: ((sourceId: String, uri: String) -> File)? = null

    /** Reads and stores a lesson's transcript, if it has one to read. Set by the app. A failure is skipped. */
    var keepTranscript: ((id: String) -> Unit)? = null

    private val _saved = MutableStateFlow(scan())
    /** Lesson id to "video" or "audio". */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Lessons being saved now, 0..1. Negative while the size is not known. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _failed = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Lessons whose last save failed, with the reason. */
    val failed: StateFlow<Map<String, String>> = _failed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val jobs = HashMap<String, Job>()
    /** One save at a time, so a whole course does not fight itself for the network. */
    private val lock = kotlinx.coroutines.sync.Mutex()

    private fun scan(): Map<String, String> {
        val all = dir.listFiles().orEmpty().filter { it.length() > 0 }
        // A reading lesson keeps only its files: a list of them, named <id>.files.
        val files = all.filter { it.name.endsWith(".files") }.associate { it.nameWithoutExtension to "files" }
        return files + all.filter { it.name.endsWith(".video") || it.name.endsWith(".audio") }.associate { it.nameWithoutExtension to it.extension }
    }

    private fun list(id: String) = File(dir, "$id.files")

    /** Saves the lesson's page and files next to its video. A file that fails is skipped; the video still counts. */
    private fun keepFiles(id: String): Int {
        val (course, _, lesson) = store.find(id) ?: return 0
        val fetch = fetchFile ?: return 0
        val names = ArrayList<String>()
        (listOf(lesson.page).filter { it.isNotBlank() } + lesson.resources.map { it.uri }).forEach { uri ->
            runCatching {
                val src = fetch(course.sourceId, uri)
                val to = File(filesDir, src.name)
                if (to.length() == 0L) src.copyTo(to, overwrite = true)
                names += to.name
            }
        }
        if (names.isNotEmpty()) list(id).writeText(names.joinToString("\n"))
        return names.size
    }

    private fun file(id: String, mode: String) = File(dir, "$id.$mode")

    fun has(id: String) = id in _saved.value
    fun mode(id: String): String? = _saved.value[id]

    fun sizeMb(id: String): Double = ((mode(id)?.let { file(id, it).length() } ?: 0L) +
        (list(id).takeIf { it.exists() }?.readLines()?.sumOf { File(filesDir, it).length() } ?: 0L)) / 1_048_576.0
    fun totalMb(): Double = (dir.listFiles().orEmpty().sumOf { it.length() } + filesDir.listFiles().orEmpty().sumOf { it.length() }) / 1_048_576.0

    /** The saved copy, for the player. A video copy also has the picture. */
    fun localStreams(id: String): Streams? {
        val m = mode(id)?.takeIf { it != "files" } ?: return null
        val f = file(id, m).takeIf { it.exists() } ?: return null
        val u = Uri.fromFile(f).toString()
        return Streams(u, if (m == "video") u else null, "saved")
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        file(id, "video").delete(); file(id, "audio").delete()
        list(id).takeIf { it.exists() }?.let { l -> l.readLines().forEach { File(filesDir, it).delete() }; l.delete() }
        _saved.update { it - id }
    }

    private fun toast(text: String) = main.post { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() }

    /** Saves lessons in the background, one after another. [audioOnly] keeps only the sound. */
    fun saveAll(ids: List<String>, audioOnly: Boolean) {
        // Lessons saved before transcripts were kept get theirs now.
        fillTranscripts(ids.filter { has(it) })
        // A lesson with a video saves the video and its files. A reading lesson saves only its files.
        val todo = ids.filter { id ->
            val l = store.find(id)?.third ?: return@filter false
            val want = if (l.media.isBlank()) "files" else if (audioOnly) "audio" else "video"
            (l.media.isNotBlank() || l.page.isNotBlank() || l.resources.isNotEmpty()) && mode(id) != want && id !in _progress.value
        }
        if (todo.isEmpty()) { toast("Already on this phone."); return }
        toast(if (todo.size == 1) "Saving for offline…" else "Saving ${todo.size} lessons for offline…")
        todo.forEach { id -> _progress.update { it + (id to -1f) } }
        for (id in todo) {
            jobs[id] = scope.launch {
                lock.lock()
                try {
                    _failed.update { it - id }
                    val media = store.find(id)?.third?.media.orEmpty()
                    runCatching {
                        if (media.isNotBlank()) save(id, audioOnly)
                        val kept = keepFiles(id)
                        runCatching { keepTranscript?.invoke(id) }
                        if (media.isBlank() && kept == 0) error("Its files could not be saved.")
                    }
                        .onSuccess { _saved.update { m -> m + (id to if (media.isBlank()) "files" else if (audioOnly) "audio" else "video") } }
                        .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _failed.update { m -> m + (id to (e.message ?: "Could not save it.")) } }
                } finally {
                    _progress.update { it - id }
                    jobs.remove(id)
                    lock.unlock()
                }
                if (_progress.value.isEmpty()) toast(if (_failed.value.isEmpty()) "Saved for offline." else "Some lessons could not be saved.")
            }
        }
    }

    /**
     * Saves one lesson's sound now and waits, for the transcriber: an audio lesson as audio, a video as video.
     * Returns false when it could not be saved.
     */
    suspend fun saveNow(id: String): Boolean {
        if (localStreams(id) != null) return true
        val lesson = store.find(id)?.third ?: return false
        if (lesson.media.isBlank()) return false
        val mode = if (lesson.kind == "video") "video" else "audio"
        lock.lock()
        try {
            if (localStreams(id) != null) return true
            _progress.update { it + (id to -1f) }
            val ok = runCatching { save(id, mode == "audio") }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.isSuccess
            if (ok) { _saved.update { it + (id to mode) }; runCatching { keepTranscript?.invoke(id) } }
            return ok
        } finally {
            _progress.update { it - id }
            lock.unlock()
        }
    }

    /** Reads the transcripts that saved lessons are missing, in the background. Needs the network once. */
    fun fillTranscripts(ids: Collection<String> = _saved.value.keys) {
        if (ids.isEmpty()) return
        scope.launch { ids.forEach { id -> runCatching { keepTranscript?.invoke(id) } } }
    }

    fun cancel(id: String) { jobs.remove(id)?.cancel(); _progress.update { it - id } }

    private suspend fun save(id: String, audioOnly: Boolean) {
        val (course, _, lesson) = store.find(id) ?: error("That lesson is gone.")
        val part = File(dir, "$id.part")
        try {
            copy(course.sourceId, lesson.media, lesson.size, part) { p -> _progress.update { it + (id to if (audioOnly && lesson.kind == "video") p * 0.6f else p) } }
            val other = if (audioOnly) "video" else "audio"
            if (audioOnly && lesson.kind == "video") {
                val out = File(dir, "$id.m4a.part")
                try {
                    audioOf(part, out) { p -> _progress.update { it + (id to 0.6f + p * 0.4f) } }
                    out.renameTo(file(id, "audio"))
                } finally { out.delete() }
            } else {
                file(id, if (audioOnly) "audio" else "video").delete()
                if (!part.renameTo(file(id, if (audioOnly) "audio" else "video"))) error("Could not keep the file.")
            }
            file(id, other).delete()
        } finally {
            part.delete()
        }
    }

    /** Copies a lesson file from its source. Local files are copied straight, network files in one stream. */
    private suspend fun copy(sourceId: String, uri: String, total: Long, to: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        store.open(sourceId, uri).use { input ->
            to.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var pos = 0L
                var last = 0L
                while (isActive) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    pos += n
                    if (pos - last > 2_000_000) { last = pos; if (total > 0) onProgress((pos.toFloat() / total).coerceIn(0f, 1f)) }
                }
                if (pos == 0L) error("The file was empty.")
            }
        }
    }

    /** Keeps only the sound of a video, as AAC in an .m4a file. Runs on the phone, with no network. */
    private suspend fun audioOf(input: File, output: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { cont ->
            val t = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Could not take the sound out of this video."))
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input))).setRemoveVideo(true).build()
            t.start(item, output.absolutePath)
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    main.postDelayed(this, 500)
                }
            }
            main.post(poll)
            cont.invokeOnCancellation { main.post { t.cancel() } }
        }
    }
}
