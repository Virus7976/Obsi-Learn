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

    private fun scan(): Map<String, String> = dir.listFiles().orEmpty()
        .filter { it.length() > 0 && (it.name.endsWith(".video") || it.name.endsWith(".audio")) }
        .associate { it.nameWithoutExtension to it.extension }

    private fun file(id: String, mode: String) = File(dir, "$id.$mode")

    fun has(id: String) = id in _saved.value
    fun mode(id: String): String? = _saved.value[id]

    fun sizeMb(id: String): Double = (mode(id)?.let { file(id, it).length() } ?: 0L) / 1_048_576.0
    fun totalMb(): Double = dir.listFiles().orEmpty().sumOf { it.length() } / 1_048_576.0

    /** The saved copy, for the player. A video copy also has the picture. */
    fun localStreams(id: String): Streams? {
        val m = mode(id) ?: return null
        val f = file(id, m).takeIf { it.exists() } ?: return null
        val u = Uri.fromFile(f).toString()
        return Streams(u, if (m == "video") u else null, "saved")
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        file(id, "video").delete(); file(id, "audio").delete()
        _saved.update { it - id }
    }

    private fun toast(text: String) = main.post { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() }

    /** Saves lessons in the background, one after another. [audioOnly] keeps only the sound. */
    fun saveAll(ids: List<String>, audioOnly: Boolean) {
        val todo = ids.filter { id -> store.find(id)?.third?.media?.isNotBlank() == true && mode(id) != (if (audioOnly) "audio" else "video") && id !in _progress.value }
        if (todo.isEmpty()) { toast("Already on this phone."); return }
        toast(if (todo.size == 1) "Saving for offline…" else "Saving ${todo.size} lessons for offline…")
        todo.forEach { id -> _progress.update { it + (id to -1f) } }
        for (id in todo) {
            jobs[id] = scope.launch {
                lock.lock()
                try {
                    _failed.update { it - id }
                    runCatching { save(id, audioOnly) }
                        .onSuccess { _saved.update { m -> m + (id to if (audioOnly) "audio" else "video") } }
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
