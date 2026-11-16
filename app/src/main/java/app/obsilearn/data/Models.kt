package app.obsilearn.data

import app.obsi.core.*
import app.obsi.core.data.*
import app.obsi.core.obsidian.*
import app.obsi.core.ui.*
import app.obsi.core.update.*
import app.obsi.core.link.*
import app.obsi.core.share.*

import kotlinx.serialization.Serializable

@Serializable
data class VideoMeta(
    val id: String,
    val title: String,
    val author: String,
    val description: String = "",
    val published: String = "",
    val durationSec: Int = 0,
    /** The lesson's own address, "obsilearn://lesson/<id>". Notes in your vault are matched to it. */
    val link: String = "",
    /** A picture for the lesson: the course cover. */
    val image: String = "",
    /** The course and module the lesson is in. [author] holds the course too, for the shared screens. */
    val course: String = "",
    val module: String = "",
) {
    val url: String get() = link.ifBlank { "obsilearn://lesson/$id" }
    val thumb: String get() = image
}








@Serializable
data class SavedVideo(
    val meta: VideoMeta,
    val chapters: List<Chapter> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val positionSec: Double = 0.0,
    val pinned: Boolean = false,
    val updatedAt: Long = 0L,
    val clippedAt: Long = 0L,
    val tags: List<String> = emptyList(),
    val category: String = "",
    /** Empty = in the main library. Otherwise the video lives in that playlist only. */
    val playlistId: String = "",
    val addedAt: Long = 0L,
    /** Where the last clip of this video went, so it can be opened in Obsidian. */
    val noteVault: String = "",
    val notePath: String = "",
    /** What was filled in the last time this video was clipped. It comes back next time. */
    val lastClip: ClipMemo? = null,
    /** When you finished it, by hand or by watching it to the end. 0 means not done. */
    val doneAt: Long = 0L,
    /** Last time it played, so what you just watched comes first. */
    val playedAt: Long = 0L,
) {
    val done: Boolean get() = doneAt > 0

    /** Where to start. A video you finished starts over, so it does not end again in a few seconds. */
    val resumeSec: Double
        get() = if (meta.durationSec > 0 && positionSec >= meta.durationSec - 10) 0.0 else positionSec
}






