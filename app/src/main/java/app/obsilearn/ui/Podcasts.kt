package app.obsilearn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsi.core.data.HttpWords
import app.obsi.core.ui.BackRow
import app.obsilearn.data.SourceErrors
import app.obsilearn.ObsiLearnApp
import app.obsilearn.data.Course
import app.obsilearn.data.Lesson
import app.obsilearn.data.SavedVideo
import app.obsilearn.data.Source
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A podcast: the course a feed makes, and its episodes newest first. */
class Show(val course: Course, val source: Source) {
    val episodes: List<Lesson> = course.lessons.sortedByDescending { it.published }
}

/** Every podcast you follow. A podcast is a feed source; the Courses tab leaves them out. */
fun podcastShows(app: ObsiLearnApp): List<Show> = app.courses.courses.mapNotNull { c ->
    app.courses.source(c.sourceId)?.takeIf { it.kind == "feed" }?.let { Show(c, it) }
}

/** True when the course comes from a podcast feed. */
fun isPodcast(app: ObsiLearnApp, c: Course) = app.courses.source(c.sourceId)?.kind == "feed"

/** Not played yet: not done, and stopped in the first seconds or not started. */
private fun fresh(id: String, byId: Map<String, SavedVideo>) = byId[id].let { it == null || (!it.done && it.positionSec < 5) }

/**
 * The Podcasts tab: new episodes from every show first, then the shows. A show opens to all its episodes.
 * An episode opens in the player with its transcript, to highlight what was said.
 */
fun LazyListScope.podcastsTab(
    shows: List<Show>,
    byId: Map<String, SavedVideo>,
    open: String?,
    onOpenShow: (String?) -> Unit,
    onPlay: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Show) -> Unit,
) {
    if (shows.isEmpty()) {
        item("pod-empty") {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Podcasts, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No podcasts yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add a show by its name or its feed address. You can read along and highlight what was said. " +
                            "When a show has its own transcript, it is used. When not, ObsiLearn makes one.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAdd) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add a podcast") }
                }
            }
        }
        return
    }
    val show = shows.firstOrNull { it.course.id == open }
    if (show != null) {
        item("pod-show-head") { BackRow(show.course.title, { onOpenShow(null) }, "${show.episodes.size} episodes") }
        item("pod-show-actions") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val next = show.episodes.firstOrNull { fresh(it.id, byId) }
                if (next != null) Button(onClick = { onPlay(next.id) }) { Text("Play newest") }
                TextButton(onClick = { onRemove(show) }) { Text("Unfollow") }
            }
        }
        items(show.episodes, key = { "pe" + it.id }) { e -> EpisodeRow(e, show, byId[e.id], showName = false) { onPlay(e.id) } }
        return
    }

    val latest = shows.flatMap { s -> s.episodes.take(20).map { s to it } }
        .filter { (_, e) -> fresh(e.id, byId) }
        .sortedByDescending { it.second.published }
        .take(8)
    if (latest.isNotEmpty()) {
        item("pod-new-head") {
            Text("New episodes", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(latest, key = { "pn" + it.second.id }) { (s, e) -> EpisodeRow(e, s, byId[e.id], showName = true) { onPlay(e.id) } }
    }
    item("pod-shows-head") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Shows", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add") }
        }
    }
    items(shows, key = { "ps" + it.course.id }) { s ->
        val new = s.episodes.count { fresh(it.id, byId) }
        Row(
            Modifier.fillMaxWidth().clickable { onOpenShow(s.course.id) }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(s.course.cover, 56)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.course.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("${s.episodes.size} episodes", if (new > 0) "$new not played" else null).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Cover(url: String, size: Int) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Podcasts, null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun EpisodeRow(e: Lesson, show: Show, v: SavedVideo?, showName: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val done = v?.done == true
    val total = (e.duration.takeIf { it > 0 } ?: v?.meta?.durationSec ?: 0).toDouble()
    val at = v?.positionSec ?: 0.0
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Cover(show.course.cover, 52)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (done) scheme.onSurfaceVariant else scheme.onSurface,
            )
            val bits = listOfNotNull(
                if (showName) show.course.title else null,
                e.published.takeIf { it > 0 }?.let { day(it) },
                when {
                    done -> "Played"
                    at > 5 && total > 0 -> minutes(total - at) + " left"
                    total > 0 -> minutes(total)
                    else -> null
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done) { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = scheme.primary); Spacer(Modifier.width(4.dp)) }
                Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (e.subs.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Subtitles, "Has a transcript", Modifier.size(14.dp), tint = scheme.onSurfaceVariant)
                }
            }
            if (!done && at > 5 && total > 0) LinearProgressIndicator(
                progress = { (at / total).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.6f).height(3.dp),
            )
            if (!showName && e.summary.isNotBlank()) Text(
                e.summary, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun day(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("MMM d"))
        else -> d.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }
}

private fun minutes(sec: Double): String {
    val m = (sec / 60).toInt().coerceAtLeast(1)
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

/** One show found by name, from Apple's public podcast list. */
private class Found(val name: String, val by: String, val art: String, val feed: String)

/** Shows whose name matches [term], from Apple's public podcast search. It needs no account or key. */
private fun searchShows(app: ObsiLearnApp, term: String): List<Found> {
    val url = android.net.Uri.parse("https://itunes.apple.com/search").buildUpon()
        .appendQueryParameter("media", "podcast").appendQueryParameter("entity", "podcast")
        .appendQueryParameter("limit", "25").appendQueryParameter("term", term).build().toString()
    val body = app.courses.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) error(HttpWords.say(r.code, "The podcast search"))
        r.body!!.string()
    }
    return Json.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val feed = s("feedUrl")
        if (feed.isBlank()) null else Found(s("collectionName"), s("artistName"), s("artworkUrl100"), feed)
    }
}

/** Add a podcast: type its name to find it, or paste its feed address. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPodcastSheet(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val isAddress = text.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    val results by produceState<Result<List<Found>>?>(null, text) {
        val t = text.trim()
        if (t.length < 2 || isAddress) { value = null; return@produceState }
        delay(400)
        value = withContext(Dispatchers.IO) { runCatching { searchShows(app, t) } }
    }

    fun add(name: String, feed: String) {
        if (adding != null) return
        adding = feed; problem = null
        val following = app.courses.state.value.sources.firstOrNull { it.kind == "feed" && it.uri.trim() == feed.trim() }
        if (following != null) { adding = null; onAdded(following.id); return }
        val s = Source("feed-" + System.currentTimeMillis().toString(36), "feed", name.ifBlank { feed }, feed.trim(), addedAt = System.currentTimeMillis())
        app.courses.addSource(s)
        scope.launch {
            val r = runCatching { app.courses.scan(s.id) }
            adding = null
            if (r.isSuccess) onAdded(s.id)
            else {
                app.courses.removeSource(s.id)
                problem = "Could not read that show's feed. " + SourceErrors.say(r.exceptionOrNull() ?: Exception())
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            Text("Add a podcast", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                text, { text = it; problem = null },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                placeholder = { Text("Show name, or feed address") },
            )
            problem?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            if (adding != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
            when {
                isAddress -> FilledTonalButton(onClick = { add("", text.trim()) }, enabled = adding == null, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text("Add this feed")
                }
                text.trim().length < 2 -> Text(
                    "Type a show's name to find it. Or paste the feed address from the show's website.",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                )
                results == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                results!!.isFailure -> Text(
                    "Could not search. Check the internet, or paste the feed address.",
                    Modifier.padding(20.dp), color = scheme.onSurfaceVariant,
                )
                results!!.getOrThrow().isEmpty() -> Text("No show by that name. Try other words.", Modifier.padding(20.dp), color = scheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(results!!.getOrThrow(), key = { it.feed }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = adding == null) { add(f.name, f.feed) }.padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(f.art, 52)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(f.by, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (adding == f.feed) Text("Adding…", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                            else Icon(Icons.Rounded.Add, "Follow", tint = scheme.primary)
                        }
                    }
                }
            }
        }
    }
}
