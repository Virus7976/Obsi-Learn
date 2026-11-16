package app.obsilearn.data

import app.obsi.core.data.JsonFileStore
import app.obsi.core.data.storeJson
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** One finished watch-through of a course. */
@Serializable
data class Round(val n: Int, val started: Long, val finished: Long, val done: Int, val total: Int)

/** A course's rounds: the one you are in now, the ones before it, and every time each lesson was finished. */
@Serializable
data class CourseRounds(
    val current: Int = 1,
    val started: Long = 0L,
    val past: List<Round> = emptyList(),
    /** Lesson id to the times it was watched to the end, oldest first, across all rounds. */
    val watches: Map<String, List<Long>> = emptyMap(),
) {
    fun times(lessonId: String) = watches[lessonId]?.size ?: 0
}

/**
 * Watching a course again. Each round starts with every lesson not done, and keeps the rounds before it.
 * Highlights and notes are never touched by a new round.
 */
class Rounds(context: Context) : JsonFileStore<Map<String, CourseRounds>>(File(context.filesDir, "rounds.json")) {
    private val _state = MutableStateFlow(readOrNull().orEmpty())
    val state: StateFlow<Map<String, CourseRounds>> = _state.asStateFlow()

    override fun encode(value: Map<String, CourseRounds>) = storeJson.encodeToString(MapSerializer(String.serializer(), CourseRounds.serializer()), value)
    override fun decode(text: String) = storeJson.decodeFromString(MapSerializer(String.serializer(), CourseRounds.serializer()), text)

    fun of(courseId: String): CourseRounds = _state.value[courseId] ?: CourseRounds()

    private fun edit(courseId: String, f: (CourseRounds) -> CourseRounds) {
        _state.update { it + (courseId to f(it[courseId] ?: CourseRounds())) }
        persist(_state.value)
    }

    /** A lesson was watched to the end. Two ends within an hour count once, so a replay of the last minute is not a new watch. */
    fun recordWatch(courseId: String, lessonId: String, now: Long = System.currentTimeMillis()) = edit(courseId) { r ->
        val list = r.watches[lessonId].orEmpty()
        if (list.isNotEmpty() && now - list.last() < HOUR) r
        else r.copy(watches = r.watches + (lessonId to list + now), started = if (r.started == 0L) now else r.started)
    }

    /** Ends this round with how far you got, and starts the next one. */
    fun startNew(courseId: String, done: Int, total: Int, now: Long = System.currentTimeMillis()) = edit(courseId) { r ->
        r.copy(current = r.current + 1, started = now, past = r.past + Round(r.current, r.started.takeIf { it > 0 } ?: now, now, done, total))
    }

    /** Another phone is on a later round: catch up to it. */
    fun catchUp(courseId: String, n: Int, done: Int, total: Int, now: Long = System.currentTimeMillis()) {
        while (of(courseId).current < n) startNew(courseId, done, total, now)
    }

    companion object { const val HOUR = 3_600_000L }
}
