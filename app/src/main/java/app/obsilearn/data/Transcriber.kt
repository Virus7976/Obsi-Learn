package app.obsilearn.data

import app.obsi.core.data.Transcripts
import app.obsi.core.data.Cue
import app.obsi.core.data.JsonFileStore
import app.obsi.core.data.TranscriptData
import app.obsi.core.data.storeJson
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** ObsiLearn's own settings, apart from the ones all Obsi apps share. */
@Serializable
data class LearnSettings(
    /** When a lesson ends, the next one in the course starts. A reading lesson waits for you. */
    val autoNext: Boolean = true,
    /** The line over PDF pages that says highlighting is in the text view was closed. */
    val pagesHintSeen: Boolean = false,
    /** PDFs shown dark: the page turned to black, the text to light, colours kept close. */
    val darkPdf: Boolean = true,
    /** "phone" (offline, free) or "cloud" (your own key, fast and exact). Only counts once [engineChosen]. */
    val engine: String = "phone",
    /** The engine was picked by hand. Until then, your key is used when one is set up, else the phone. */
    val engineChosen: Boolean = false,
    /** Which speech model the phone uses. A key of [Transcriber.phoneModels]. */
    val phoneModel: String = "en-us",
    /** Any service that speaks the OpenAI transcription API: OpenAI, Groq, or your own server. */
    val cloudUrl: String = "https://api.groq.com/openai/v1",
    val cloudKey: String = "",
    val cloudModel: String = "whisper-large-v3-turbo",
    /** Blank lets the service find the language. Else a code like "en". */
    val language: String = "",
    /** Write the transcript as a .vtt file next to the video too, when the source lets the app write. */
    val keepVtt: Boolean = false,
    /** Write course progress into the vault as notes. */
    val progressNotes: Boolean = true,
    /** The folder in the vault for course notes. */
    val notesFolder: String = "ObsiLearn",
    /** The notes folder is in Obsidian's Excluded files, so it stays out of search. */
    val hideFromSearch: Boolean = false,
)

class LearnSettingsStore(context: Context) : JsonFileStore<LearnSettings>(File(context.filesDir, "learn.json")) {
    private val _state = MutableStateFlow(readOrNull() ?: LearnSettings())
    val state: StateFlow<LearnSettings> = _state.asStateFlow()
    val value: LearnSettings get() = _state.value
    override fun encode(value: LearnSettings) = storeJson.encodeToString(LearnSettings.serializer(), value)
    override fun decode(text: String) = storeJson.decodeFromString(LearnSettings.serializer(), text)
    fun update(block: (LearnSettings) -> LearnSettings) { _state.update(block); persist(_state.value) }
}

/** Makes transcripts for lessons that have none. One lesson at a time, in the background. */
class Transcriber(
    private val context: Context,
    private val store: CourseStore,
    private val offline: Offline,
    private val library: Library,
    private val settings: () -> LearnSettings,
) {
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** True when transcripts go to the service with your key, false when the phone makes them. */
    fun usesCloud(s: LearnSettings = settings()): Boolean =
        if (s.engineChosen) s.engine == "cloud" else app.obsi.core.ai.Ai.store(context).value.speech != null

    /** Lessons waiting or being transcribed. 0..1, negative while waiting its turn. */
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    private val _done = MutableStateFlow<Set<String>>(emptySet())
    /** Lessons that got a transcript since the app started. */
    val done: StateFlow<Set<String>> = _done.asStateFlow()

    private val _failed = MutableStateFlow<Map<String, String>>(emptyMap())
    val failed: StateFlow<Map<String, String>> = _failed.asStateFlow()

    /** What the phone model download is doing: null when idle, else 0..1. */
    val modelProgress = MutableStateFlow<Float?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val jobs = HashMap<String, Job>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun toast(text: String) = main.post { android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show() }

    private val http = store.http.newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES).callTimeout(0, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun hasTranscript(id: String) = library.loadTranscript(id) != null

    /** Transcribes these lessons, one after another. Lessons that have a transcript are skipped, unless [redo]. */
    fun start(ids: List<String>, redo: Boolean = false) {
        val s = settings()
        if (usesCloud(s) && app.obsi.core.ai.Ai.store(context).value.speech == null) { toast("Set up online transcripts first: Settings, then AI service."); return }
        val todo = ids.filter { id ->
            id !in _progress.value && store.find(id)?.third?.media?.isNotBlank() == true && (redo || !hasTranscript(id))
        }
        if (todo.isEmpty()) { toast("Those lessons have transcripts already."); return }
        toast(if (todo.size == 1) "Making a transcript…" else "Making ${todo.size} transcripts…")
        todo.forEach { id -> _progress.update { it + (id to -1f) } }
        for (id in todo) {
            jobs[id] = scope.launch {
                lock.withLock {
                    _failed.update { it - id }
                    runCatching { transcribe(id) }
                        .onSuccess { data ->
                            library.saveTranscript(id, data)
                            _done.update { it + id }
                        }
                        .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _failed.update { it + (id to (e.message ?: "It did not work.")) } }
                    _progress.update { it - id }
                    jobs.remove(id)
                }
                if (_progress.value.isEmpty()) toast(if (_failed.value.isEmpty()) "Transcripts are ready." else "Some transcripts could not be made: " + _failed.value.values.first())
            }
        }
    }

    fun cancel(id: String) { jobs.remove(id)?.cancel(); _progress.update { it - id } }
    fun cancelAll() { jobs.values.forEach { it.cancel() }; jobs.clear(); _progress.value = emptyMap() }

    private fun report(id: String, p: Float) = _progress.update { if (id in it) it + (id to p.coerceIn(0f, 1f)) else it }

    private suspend fun transcribe(id: String): TranscriptData {
        val (course, _, lesson) = store.find(id) ?: error("That lesson is gone.")
        // A podcast can put in different ads each time it is downloaded, so a transcript of one download
        // drifts against another. Save it first: the transcript and the player then use the same copy.
        if (store.source(course.sourceId)?.kind in setOf("feed", "links") && offline.localStreams(id) == null) {
            report(id, 0f)
            if (!offline.saveNow(id)) error("Could not save the episode to make its transcript. Check the connection.")
        }
        // The copy on the phone is fastest and works with the source gone.
        val local = offline.localStreams(id)
        val (url, headers) = if (local != null) local.audioUrl to emptyMap() else store.playable(course.sourceId, lesson.media)
        val uri = Uri.parse(url)
        val hint = listOf(course.title, lesson.title).filter { it.isNotBlank() }.joinToString(". ")
        return if (usesCloud()) cloud(id, uri, headers, hint) else phone(id, uri, headers)
    }

    // ---------- on the phone (Vosk) ----------

    private fun modelDir(key: String) = File(context.filesDir, "speech/$key")

    fun modelReady(key: String = settings().phoneModel) = File(modelDir(key), "conf").exists() || File(modelDir(key), "am").exists()

    /** Downloads and unpacks the phone speech model, once. */
    private suspend fun ensureModel(key: String): File {
        val dir = modelDir(key)
        if (modelReady(key)) return dir
        val url = phoneModels[key]?.second ?: error("That speech model is not known.")
        toast("Getting the speech model. This happens once.")
        modelProgress.value = 0f
        try {
            val zip = File(context.cacheDir, "speech-$key.zip")
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("Could not get the speech model (${r.code}).")
                val body = r.body!!
                val total = body.contentLength()
                zip.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024); var pos = 0L
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); pos += n; if (total > 0) modelProgress.value = pos.toFloat() / total }
                    }
                }
            }
            val tmp = File(dir.parentFile, "$key.part").also { it.deleteRecursively(); it.mkdirs() }
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    // The zip has one top folder. Its contents go straight into the model folder.
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isBlank() || rel.contains("..")) continue
                    val f = File(tmp, rel)
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                }
            }
            zip.delete()
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) error("Could not keep the speech model.")
            return dir
        } finally {
            modelProgress.value = null
        }
    }

    private suspend fun phone(id: String, uri: Uri, headers: Map<String, String>): TranscriptData {
        val key = settings().phoneModel
        val dir = ensureModel(key)
        val model = org.vosk.Model(dir.absolutePath)
        val rec = org.vosk.Recognizer(model, app.obsi.core.speech.Pcm.RATE.toFloat())
        val words = ArrayList<app.obsi.core.speech.Word>()
        try {
            rec.setWords(true)
            app.obsi.core.speech.Pcm.decode(context, uri, headers, { p -> report(id, p) }) { block ->
                if (rec.acceptWaveForm(block, block.size)) words += parseVosk(rec.result)
            }
            words += parseVosk(rec.finalResult)
        } finally {
            rec.close(); model.close()
        }
        if (words.isEmpty()) error("No speech was heard in this lesson.")
        return TranscriptData(lang = key.substringBefore('-'), auto = true, cues = app.obsi.core.speech.Speech.cues(words))
    }

    private fun parseVosk(text: String): List<app.obsi.core.speech.Word> = runCatching {
        json.parseToJsonElement(text).jsonObject["result"]?.jsonArray.orEmpty().map { it.jsonObject }.mapNotNull { w ->
            val word = w["word"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            app.obsi.core.speech.Word(word, w["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0, w["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
        }
    }.getOrDefault(emptyList())

    // ---------- your own key (OpenAI-style API) ----------

    private suspend fun cloud(id: String, uri: Uri, headers: Map<String, String>, hint: String): TranscriptData {
        // The service, key and model come from the AI setup every Obsi app shares.
        val ai = app.obsi.core.ai.Ai.store(context).value
        val p = ai.speech ?: error("Set up online transcripts first: Settings, then AI service.")
        return app.obsi.core.speech.Cloud(http, ai.url(p), ai.key(p), p.speechModel, settings().language, hint).transcribe(context, uri, headers) { f -> report(id, f * 0.95f) }
    }

    companion object {
        /**
         * Small speech models that run on a phone, from alphacephei.com/vosk/models (Apache 2.0).
         * Key to (name, address).
         */
        val phoneModels: Map<String, Pair<String, String>> = linkedMapOf(
            "en-us" to ("English" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
            "es" to ("Spanish" to "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
            "fr" to ("French" to "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"),
            "de" to ("German" to "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"),
            "pt" to ("Portuguese" to "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),
            "it" to ("Italian" to "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"),
            "nl" to ("Dutch" to "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip"),
        )
    }


}
