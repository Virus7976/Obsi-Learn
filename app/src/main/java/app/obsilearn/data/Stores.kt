package app.obsilearn.data

import app.obsi.core.*
import app.obsi.core.data.*
import app.obsi.core.obsidian.*
import app.obsi.core.ui.*
import app.obsi.core.update.*
import app.obsi.core.link.*
import app.obsi.core.share.*

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class Library(private val context: Context) :
    JsonFileStore<List<SavedVideo>>(File(context.filesDir, "library.json")) {
    private val _state = MutableStateFlow(readOrNull().orEmpty())
    val videos: StateFlow<List<SavedVideo>> = _state.asStateFlow()

    override fun encode(value: List<SavedVideo>) = storeJson.encodeToString(ListSerializer(SavedVideo.serializer()), value)
    override fun decode(text: String) = storeJson.decodeFromString(ListSerializer(SavedVideo.serializer()), text)

    fun get(id: String): SavedVideo? = _state.value.firstOrNull { it.meta.id == id }

    fun upsert(video: SavedVideo) {
        _state.update { list ->
            val without = list.filterNot { it.meta.id == video.meta.id }
            listOf(video.copy(updatedAt = System.currentTimeMillis())) + without
        }
        persist(_state.value)
    }

    /**
     * Brings the library in line with the courses: a new lesson gets a place, a renamed one its new title.
     * Highlights, progress and notes stay. A lesson that left its course stays too, so nothing you made is lost.
     */
    fun sync(metas: List<VideoMeta>) {
        val byId = _state.value.associateBy { it.meta.id }
        var changed = false
        val now = System.currentTimeMillis()
        val next = ArrayList<SavedVideo>(maxOf(byId.size, metas.size))
        val seen = HashSet<String>()
        for (m in metas) {
            seen += m.id
            val old = byId[m.id]
            if (old == null) { changed = true; next += SavedVideo(meta = m, addedAt = now) }
            else {
                val meta = m.copy(durationSec = maxOf(m.durationSec, old.meta.durationSec), published = old.meta.published)
                if (meta != old.meta) changed = true
                next += old.copy(meta = meta)
            }
        }
        _state.value.forEach { if (it.meta.id !in seen) next += it }
        if (!changed) return
        _state.value = next
        persist(next)
    }

    /** Change one video in place without moving it to the top. */
    fun edit(id: String, block: (SavedVideo) -> SavedVideo) {
        _state.update { list -> list.map { if (it.meta.id == id) block(it) else it } }
        persist(_state.value)
    }

    fun remove(id: String) {
        _state.update { list -> list.filterNot { it.meta.id == id } }
        persist(_state.value)
        File(transcriptDir(), "$id.json").delete()
    }

    private fun transcriptDir() = File(context.filesDir, "transcripts").also { it.mkdirs() }

    fun saveTranscript(id: String, data: TranscriptData) {
        runCatching {
            File(transcriptDir(), "$id.json").writeText(storeJson.encodeToString(TranscriptData.serializer(), data))
        }
    }

    fun loadTranscript(id: String): TranscriptData? = runCatching {
        storeJson.decodeFromString(TranscriptData.serializer(), File(transcriptDir(), "$id.json").readText())
    }.getOrNull()
}
