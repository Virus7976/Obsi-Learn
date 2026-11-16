package app.obsilearn.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * One playable copy of a lesson. [via] is "saved" for the copy on the phone, else the source kind.
 * [headers] go with every request, for a WebDAV login.
 */
data class Streams(
    val audioUrl: String,
    val videoUrl: String?,
    val via: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Finds where a lesson plays from: the copy on the phone first, then its source. */
class LessonStreams(private val store: CourseStore, private val offline: () -> Offline) {
    /** The login for a share address, for the player's share reader. */
    fun sourceFor(uri: String): Source? = store.sourceFor(uri)

    /** Looked up off the main thread: a Google Drive lesson asks Google for a token first. */
    fun candidates(id: String): Flow<Streams> = flow {
        offline().localStreams(id)?.let { emit(it) }
        val (course, _, lesson) = store.find(id) ?: return@flow
        if (lesson.media.isBlank()) return@flow
        val (url, headers) = store.playable(course.sourceId, lesson.media)
        val kind = store.source(course.sourceId)?.kind.orEmpty()
        emit(Streams(url, if (lesson.kind == "video") url else null, kind, headers))
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)
}
