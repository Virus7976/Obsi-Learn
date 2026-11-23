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
    /** A PDF as text leaves out what it prints on page after page (a copyright line, page numbers) and the page labels. */
    val cleanPdf: Boolean = true,
    /** "phone" (offline, free) or "cloud" (your own key, fast and exact). Only counts once [engineChosen]. */
    val engine: String = "phone",
    /** The engine was picked by hand. Until then, your key is used when one is set up, else the phone. */
    val engineChosen: Boolean = false,
    /** Which speech model the phone uses: a key of [PhoneSpeech.Model]. Anything else: the one that suits the phone. */
    val phoneModel: String = "",
    /** After a transcript is made, the text model fixes misheard words, stops and capitals. Only the text is sent. */
    val cleanUp: Boolean = false,
    /** The quick buttons at the top of a lesson's menu, in order. Keys of the lesson menu's quick actions. */
    val quick: List<String> = listOf("find", "follow", "text", "listen"),
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

    /** "phone" or "cloud" for one lesson's next transcript, picked in its menu. It wins over the setting once. */
    private val engineFor = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Is a speech service with a key set up? */
    fun hasKey(): Boolean = app.obsi.core.ai.Ai.store(context).value.speech != null

    /**
     * Transcribes these lessons, one after another. Lessons that have a transcript are skipped, unless [redo].
     * [engine] is "phone" or "cloud" to use that one this time, or null for the setting.
     */
    fun start(ids: List<String>, redo: Boolean = false, engine: String? = null) {
        val s = settings()
        val cloud = engine?.let { it == "cloud" } ?: usesCloud(s)
        if (cloud && !hasKey()) { toast("Set up online transcripts first: Settings, then AI service."); return }
        val todo = ids.filter { id ->
            id !in _progress.value && store.find(id)?.third?.media?.isNotBlank() == true && (redo || !hasTranscript(id))
        }
        if (todo.isEmpty()) { toast("Those lessons have transcripts already."); return }
        todo.forEach { id -> engine?.let { engineFor[id] = it } ?: engineFor.remove(id) }
        toast(if (todo.size == 1) "Making a transcript…" else "Making ${todo.size} transcripts…")
        queue(todo, "Transcripts are ready.", "Some transcripts could not be made: ") { transcribe(it) }
    }

    /**
     * Makes a lesson's transcript better with the text model: misheard words, stops and capitals. The sound
     * is not used again, only the text, so it is quick and costs few tokens.
     */
    fun refine(id: String) {
        val now = library.loadTranscript(id) ?: run { toast("This lesson has no transcript yet."); return }
        if (!app.obsi.core.ai.Ai.readyNow) { toast("No AI is working right now. See Settings, then AI service."); return }
        if (id in _progress.value) return
        toast("Making the transcript better…")
        queue(listOf(id), "The transcript is better now.", "The transcript could not be made better: ") {
            val (course, _, lesson) = store.find(id) ?: error("That lesson is gone.")
            val hint = listOf(course.title, lesson.title).filter { it.isNotBlank() }.joinToString(". ")
            val words = app.obsi.core.speech.Speech.words(now.cues)
            val fixed = app.obsi.core.ai.CleanTranscript.run(context, words, hint) { f -> report(id, f) }
            now.copy(cues = app.obsi.core.speech.Speech.cues(fixed))
        }
    }

    /** Runs [work] for each lesson, one at a time, and saves what it makes as the lesson's transcript. */
    private fun queue(todo: List<String>, ready: String, failedText: String, work: suspend (String) -> TranscriptData) {
        todo.forEach { id -> _progress.update { it + (id to -1f) } }
        for (id in todo) {
            jobs[id] = scope.launch {
                lock.withLock {
                    _failed.update { it - id }
                    runCatching { work(id) }
                        .onSuccess { data ->
                            library.saveTranscript(id, data)
                            _done.update { it + id }
                        }
                        .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _failed.update { it + (id to (e.message ?: "It did not work.")) } }
                    _progress.update { it - id }
                    jobs.remove(id)
                    engineFor.remove(id)
                }
                if (_progress.value.isEmpty()) toast(if (_failed.value.isEmpty()) ready else failedText + _failed.value.values.first())
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
        val clean = settings().cleanUp && app.obsi.core.ai.Ai.readyNow
        // With clean-up on, the engine gets most of the bar and the clean-up the rest.
        val share = if (clean) 0.85f else 1f
        val cloudNow = engineFor[id]?.let { it == "cloud" } ?: usesCloud()
        val data = if (cloudNow) cloud(id, uri, headers, hint, share) else phone(id, uri, headers, share)
        if (!clean) return data
        val words = app.obsi.core.speech.Speech.words(data.cues)
        val fixed = app.obsi.core.ai.CleanTranscript.run(context, words, hint) { f -> report(id, share + (1 - share) * f) }
        return data.copy(cues = app.obsi.core.speech.Speech.cues(fixed))
    }

    // ---------- on the phone (sherpa-onnx) ----------

    private fun modelDir(key: String) = File(context.filesDir, "speech/$key")
    private val vadFile get() = File(context.filesDir, "speech/silero_vad.onnx")

    /** The model the phone uses now. */
    fun phoneModel(): PhoneSpeech.Model = PhoneSpeech.pick(context, settings().phoneModel)

    fun modelReady(m: PhoneSpeech.Model = phoneModel()) = vadFile.exists() && m.files.all { File(modelDir(m.key), it).exists() }

    /** Fetches one file to [to], through a .part file, so a cut-off download is never taken for a whole one. */
    private fun fetch(url: String, to: File, onBytes: (Long) -> Unit) {
        if (to.exists()) return
        to.parentFile?.mkdirs()
        val part = File(to.path + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Could not get the speech model (${r.code}).")
            part.outputStream().use { out ->
                r.body!!.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); onBytes(n.toLong()) }
                }
            }
        }
        if (!part.renameTo(to)) error("Could not keep the speech model.")
    }

    /** Downloads the phone speech model, once. Old models from before are removed to free the space. */
    private suspend fun ensureModel(m: PhoneSpeech.Model): File {
        val dir = modelDir(m.key)
        if (modelReady(m)) return dir
        toast("Getting the speech model (${m.mb} MB). This happens once.")
        modelProgress.value = 0f
        try {
            File(context.filesDir, "speech").listFiles()?.forEach { f ->
                if (f.isDirectory && PhoneSpeech.Model.of(f.name) == null) f.deleteRecursively()
            }
            val total = m.mb * 1_000_000L
            var got = 0L
            val count: (Long) -> Unit = { n -> got += n; modelProgress.value = (got.toFloat() / total).coerceIn(0f, 1f) }
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                fetch(PhoneSpeech.VAD_URL, vadFile) {}
                m.files.forEach { fetch(m.url(it), File(dir, it), count) }
            }
            return dir
        } finally {
            modelProgress.value = null
        }
    }

    private suspend fun phone(id: String, uri: Uri, headers: Map<String, String>, share: Float): TranscriptData {
        val m = phoneModel()
        val dir = ensureModel(m)
        val words = PhoneSpeech.transcribe(context, m, dir, vadFile, uri, headers) { p -> report(id, p * share) }
        if (words.isEmpty()) error("No speech was heard in this lesson.")
        val lang = if (m == PhoneSpeech.Model.Moonshine) "en" else settings().language
        return TranscriptData(lang = lang, auto = true, cues = app.obsi.core.speech.Speech.cues(words))
    }

    // ---------- your own key (OpenAI-style API) ----------

    private suspend fun cloud(id: String, uri: Uri, headers: Map<String, String>, hint: String, share: Float): TranscriptData {
        // The service, key and model come from the AI setup every Obsi app shares.
        val ai = app.obsi.core.ai.Ai.store(context).value
        val p = ai.speech ?: error("Set up online transcripts first: Settings, then AI service.")
        return app.obsi.core.speech.Cloud(http, ai.url(p), ai.key(p), p.speechModel, settings().language, hint).transcribe(context, uri, headers) { f -> report(id, f * share) }
    }



}
