package app.obsilearn.data

import android.content.Context
import app.obsi.core.data.JsonFileStore
import app.obsi.core.data.storeJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** How a file was last shown: as text you can highlight, or as its own pages; and the page you were on. */
@Serializable
data class DocPlace(val mode: String = "", val page: Int = 0)

/** Where you were in each lesson file, so it opens there again. The place in the text is kept with the file's item. */
class DocPlaces(context: Context) : JsonFileStore<Map<String, DocPlace>>(File(context.filesDir, "doc-places.json")) {
    private val _state = MutableStateFlow(readOrNull().orEmpty())
    val state: StateFlow<Map<String, DocPlace>> = _state.asStateFlow()

    fun of(id: String): DocPlace = _state.value[id] ?: DocPlace()

    fun set(id: String, block: (DocPlace) -> DocPlace) {
        _state.update { it + (id to block(it[id] ?: DocPlace())) }
        persist(_state.value)
    }

    private val ser = MapSerializer(String.serializer(), DocPlace.serializer())
    override fun encode(value: Map<String, DocPlace>) = storeJson.encodeToString(ser, value)
    override fun decode(text: String) = storeJson.decodeFromString(ser, text)
}
