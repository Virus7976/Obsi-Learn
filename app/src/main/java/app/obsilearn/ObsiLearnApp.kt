package app.obsilearn

import app.obsi.core.data.Transcripts

import app.obsi.core.*
import app.obsi.core.data.*
import app.obsi.core.obsidian.*
import app.obsi.core.ui.*
import app.obsi.core.update.*
import app.obsi.core.link.*
import app.obsi.core.share.*

import android.app.Application
import app.obsilearn.data.Library
import app.obsilearn.data.SourceErrors
import app.obsi.core.data.PlayQueue
import app.obsi.core.data.Playlists
import app.obsi.core.data.SettingsStore
import app.obsi.core.data.VaultStore
import app.obsi.core.update.SelfUpdate
import app.obsi.core.obsidian.ClipperImport
import app.obsilearn.player.PlayerHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ObsiLearnApp : Application(), app.obsi.core.ObsiHost {
    override lateinit var settings: SettingsStore
        private set
    lateinit var library: Library
        private set
    /** Your sources and the courses in them. */
    lateinit var courses: app.obsilearn.data.CourseStore
        private set
    lateinit var player: PlayerHost
        private set
    override lateinit var updates: SelfUpdate
        private set
    override lateinit var vaults: VaultStore
        private set
    lateinit var playlists: Playlists
        private set
    lateinit var queue: PlayQueue
        private set
    /** ObsiLearn's own settings: transcripts and course notes. */
    lateinit var learn: app.obsilearn.data.LearnSettingsStore
        private set
    /** Makes transcripts for lessons that have none. */
    lateinit var transcriber: app.obsilearn.data.Transcriber
        private set
    /** Lessons kept on the phone. */
    lateinit var offline: app.obsilearn.data.Offline
        private set
    lateinit var reviews: app.obsilearn.data.Reviews
        private set

    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override val recentTags: kotlinx.coroutines.flow.StateFlow<List<String>> by lazy {
        settings.state.map { it.recentTags }.stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, settings.value.recentTags)
    }

    /** A video another Obsi app asked to open, and the second to start at. The app opens it and clears it. */
    val openItem = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Double>?>(null)

    /** A highlight picked in search: the reader scrolls to it when it opens. Video id and second. */
    val pendingSeek = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Double>?>(null)

    /** Set when the review reminder was tapped. The app opens Review and clears it. */
    val openReview = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Set when the media notification was tapped. The app opens the playing video and clears it. */
    val openNowPlaying = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** A video whose new note needs your details before it saves. The app shows the save sheet for it. */
    val askSave = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** True while the app is on screen. */
    @Volatile var inForeground = false

    /** Fires with the video id when the queue starts the next video by itself. */
    val advanced = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    override fun onCreate() {
        super.onCreate()
        app.obsi.core.Brand.init(app.obsi.core.ObsiApp.Learn)
        app.obsi.core.share.SharedHighlights.source = ::sharedHighlights
        app.obsi.core.share.SharedSetup.source = { app.obsi.core.share.SharedSetup.of(settings.value) }
        app.obsi.core.share.SharedReview.due = { reviews.due(library.videos.value).map { shared(it.video, it.highlight) } }
        app.obsi.core.share.SharedReview.act = { key, action -> reviews.act(key, action) }
        settings = SettingsStore(this)
        vaults = VaultStore(this) { settings.value.vaultFolders }
        updates = SelfUpdate(this, settings)
        library = Library(this)
        reviews = app.obsilearn.data.Reviews(this)
        playlists = Playlists(this)
        queue = PlayQueue(settings)
        learn = app.obsilearn.data.LearnSettingsStore(this)
        courses = app.obsilearn.data.CourseStore(this)
        offline = app.obsilearn.data.Offline(this, courses)
        // The decoder for transcripts reads shares through the app's share reader.
        app.obsi.core.speech.Pcm.customSource = { u -> if (u.scheme == "smb") app.obsilearn.data.Smb.media(courses.sourceFor(u.toString()), u.toString()) else null }
        transcriber = app.obsilearn.data.Transcriber(this, courses, offline, library) { learn.value }
        player = PlayerHost(this, app.obsilearn.data.LessonStreams(courses) { offline }) { settings.value }
        player.artworkFor = { id -> library.get(id)?.meta?.image?.let { img -> if (img.startsWith("hub")) courses.find(id)?.let { courses.playable(it.first.sourceId, img).first } else img } }
        // Every lesson has a place in the library, so search, highlights and review work for all of them.
        appScope.launch { courses.state.collect { library.sync(lessonMetas()) } }
        // Long saves and transcripts keep running with the app in the background.
        appScope.launch {
            kotlinx.coroutines.flow.combine(offline.progress, transcriber.progress) { a, b -> a.size + b.size }.distinctUntilChanged().collect { n ->
                WorkService.update(this@ObsiLearnApp, n)
            }
        }
        watchProgressNotes()
        player.onEnded = { player.state.value.videoId?.let { markWatched(it); autoClip(it) }; playNextInQueue() }
        player.onWatched = { markWatched(it); autoClip(it) }
        player.onPosition = { id, sec -> library.edit(id) { it.copy(positionSec = sec, playedAt = System.currentTimeMillis()) } }
        // The reminder alarm follows the settings.
        appScope.launch {
            settings.state.map { it.reviewReminder to it.reviewHour }.distinctUntilChanged().collect { (on, hour) ->
                ReviewReminder.schedule(this@ObsiLearnApp, on, hour)
            }
        }
        seedFromBundledClipperSettings()
        updates.checkIfDue()
    }

    /** Saves the highlights to Obsidian when a video ends, if that is switched on. */
    fun autoClip(id: String) {
        if (!settings.value.autoClip) return
        // It runs near the end and again at the end. The second run has work only if something changed.
        val now = library.get(id)?.highlights ?: return
        if (autoClipped[id] == now) return
        autoClipped[id] = now
        appScope.launch {
            val message = runCatching { app.obsilearn.obsidian.AutoClip.run(this@ObsiLearnApp, id) }.getOrNull() ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                // A new note with empty fields waits for you: the save sheet opens, or a notification asks.
                if (message == app.obsilearn.obsidian.AutoClip.ASK) {
                    askSave.value = id
                    if (!inForeground) SaveAsk.post(this@ObsiLearnApp, id)
                    return@withContext
                }
                android.widget.Toast.makeText(this@ObsiLearnApp, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private val autoClipped = HashMap<String, List<app.obsi.core.data.Highlight>>()

    /** What each lesson is called and where it sits, for the library. */
    private fun lessonMetas(): List<app.obsilearn.data.VideoMeta> = courses.state.value.courses.flatMap { c ->
        c.modules.flatMap { m ->
            m.lessons.map { l ->
                app.obsilearn.data.VideoMeta(
                    id = l.id, title = l.title, author = c.title, description = m.title,
                    link = "obsilearn://lesson/" + l.id, image = c.cover, course = c.title, module = m.title,
                )
            }
        }
    }

    private val written = HashMap<String, String>()

    /**
     * Writes a course's note in the vault when its progress changes and nothing is playing,
     * so a sync tool gets a few calm writes and not one every few seconds.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchProgressNotes() {
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(library.videos, player.state.map { it.playing }.distinctUntilChanged(), learn.state) { v: List<app.obsilearn.data.SavedVideo>, playing: Boolean, l: app.obsilearn.data.LearnSettings -> Triple(v, playing, l) }
                .debounce(2500L)
                .collect { (videos, playing, l) ->
                    if (!l.progressNotes) return@collect
                    val byId = videos.associateBy { it.meta.id }
                    for (c in courses.courses) {
                        val sig = c.lessons.joinToString(",") { les ->
                            val v = byId[les.id]
                            (if (v?.done == true) "x" else "") + ((v?.positionSec ?: 0.0) / 10).toInt() + "/" + (v?.highlights?.size ?: 0)
                        } + c.title
                        if (written[c.id] == sig) continue
                        // While it plays, only a lesson that just got done is worth a write.
                        val before = written[c.id]
                        if (playing && before != null && before.count { it == 'x' } == sig.count { it == 'x' }) continue
                        if (app.obsilearn.data.ProgressNotes.write(this@ObsiLearnApp, settings.value, l.notesFolder, c, byId)) written[c.id] = sig
                    }
                }
        }
    }

    /** Reads every course note in the vault and takes what another phone did there. Runs when the app comes back. */
    fun pullProgressNotes() {
        if (!learn.value.progressNotes) return
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val byId = library.videos.value.associateBy { it.meta.id }
            for (c in courses.courses) {
                val text = app.obsilearn.data.ProgressNotes.read(this@ObsiLearnApp, settings.value, learn.value.notesFolder, c) ?: continue
                val changes = app.obsilearn.data.ProgressNotes.merge(app.obsilearn.data.ProgressNotes.parse(text), byId)
                for ((id, m) in changes) {
                    // The lesson playing now keeps its own place.
                    if (player.state.value.videoId == id && player.state.value.playing) continue
                    library.edit(id) { v ->
                        v.copy(
                            doneAt = if (m.done && !v.done) System.currentTimeMillis() else v.doneAt,
                            positionSec = m.pos, playedAt = m.at,
                        )
                    }
                }
            }
        }
    }

    /**
     * A copy of a lesson file on the phone, to open in another app. A file that is on the phone already is used
     * as it is. Returns a content address another app may read.
     */
    fun localCopy(sourceId: String, uri: String): android.net.Uri {
        if (uri.startsWith("content:")) return android.net.Uri.parse(uri)
        val dir = java.io.File(cacheDir, "open").also { it.mkdirs() }
        val name = android.net.Uri.decode(uri.substringAfterLast('/').substringBefore('?')).replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
        val f = java.io.File(dir, app.obsilearn.data.Layout.id(uri).take(8) + "-" + name)
        if (!f.exists() || f.length() == 0L) courses.open(sourceId, uri).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    /** Opens a file in the app that shows it best: a PDF reader, a browser for a page, Office for slides. */
    fun openExternally(context: android.content.Context, uri: android.net.Uri) {
        val mime = contentResolver.getType(uri) ?: android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(uri.toString().substringAfterLast('.').lowercase()) ?: "*/*"
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(android.content.Intent.createChooser(i, "Open with").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { android.widget.Toast.makeText(context, "No app on this phone opens that file.", android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** The picture for a course card. Pictures that need a login are fetched once and kept. */
    fun coverModel(c: app.obsilearn.data.Course): Any? {
        if (c.cover.isBlank()) return null
        if (c.cover.startsWith("content:") || c.cover.startsWith("http")) {
            val kind = courses.source(c.sourceId)?.kind
            if (kind != "webdav" || c.cover.startsWith("content:")) return c.cover
        }
        val f = java.io.File(filesDir, "covers/${c.id}.img")
        if (f.exists() && f.length() > 0) return f
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                f.parentFile?.mkdirs()
                courses.open(c.sourceId, c.cover).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                // Tell the cards to look again.
                courses.editCourse(c.id) { it.copy(scannedAt = it.scannedAt + 1) }
            }.onFailure { f.delete() }
        }
        return null
    }

    /** Adds a video link or file as a lesson in "Links", and returns the lesson id. */
    fun addLink(link: String): String {
        val sid = "links"
        val s = courses.source(sid)
        val lines = (s?.uri.orEmpty().lines().filter { it.isNotBlank() } + link.trim()).distinct()
        courses.addSource(app.obsilearn.data.Source(sid, "links", "Links", lines.joinToString("\n"), addedAt = s?.addedAt ?: System.currentTimeMillis()))
        kotlinx.coroutines.runBlocking { runCatching { courses.scan(sid) } }
        return app.obsilearn.data.Layout.id(sid, link.trim())
    }

    /** Reads a source again and tells you how it went. */
    fun rescan(sourceId: String, done: (String) -> Unit = {}) {
        appScope.launch {
            val msg = runCatching { courses.scan(sourceId) }.fold(
                { n -> if (n == 0) "No lessons found there." else if (n == 1) "Found 1 course." else "Found $n courses." },
                { e -> SourceErrors.say(e) },
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { done(msg) }
        }
    }

    /** Takes a video off this phone: playback, queue, saved audio, playlists and library. */
    fun removeVideo(id: String) {
        if (player.state.value.videoId == id) player.stop()
        queue.remove(id)
        offline.remove(id)
        playlists.all.value.filter { id in it.videoIds }.forEach { playlists.removeVideo(it.id, id) }
        library.remove(id)
    }

    /** Done by hand. It works like watching to the end, and a finished video has no place in the queue. */
    fun setDone(id: String, done: Boolean) {
        if (done) { markWatched(id); queue.remove(id) }
        else library.edit(id) { it.copy(doneAt = 0L) }
    }

    /** A video was watched to the end. It counts as done, and leaves every playlist that is set to drop watched videos. */
    fun markWatched(id: String) {
        library.edit(id) { if (it.done) it else it.copy(doneAt = System.currentTimeMillis()) }
        playlists.all.value.filter { it.removeWhenWatched && id in it.videoIds }.forEach { p ->
            playlists.removeVideo(p.id, id)
            // An imported playlist hides its videos from the library. Let this one go there.
            if (library.get(id)?.playlistId == p.id) library.edit(id) { it.copy(playlistId = "") }
        }
    }

    /** Every lesson is in the library already. True when the lesson is known. */
    suspend fun addToLibrary(id: String): Boolean = library.get(id) != null

    /** Starts the next queued video. Skips ids that are no longer in the library. */
    fun playNextInQueue() {
        while (true) {
            val id = queue.pop() ?: return
            val v = library.get(id) ?: continue
            player.load(id, v.meta.title, v.meta.author, 0.0, force = true)
            advanced.tryEmit(id)
            return
        }
    }

    /** First launch: load the clipper templates that ship inside the app. */
    override fun sampleVariables(s: app.obsi.core.data.AppSettings): Map<String, String> {
        val v = library.videos.value.maxByOrNull { maxOf(it.playedAt, it.updatedAt) } ?: return emptyMap()
        val lines = library.loadTranscript(v.meta.id)?.let { Transcripts.toLines(it.cues, s.lineLength, v.chapters) }.orEmpty()
        return app.obsilearn.obsidian.NoteBuilder.variables(v, lines, s)
    }

    override fun previewLockScreen(context: android.content.Context) = LockScreenActivity.launch(context)

    @androidx.compose.runtime.Composable
    override fun IconPicker() = app.obsilearn.ui.AppIconPicker()

    /** Every highlight, for the other Obsi apps to show. */
    private fun sharedHighlights(): List<app.obsi.core.share.SharedHighlight> =
        library.videos.value.flatMap { v -> v.highlights.map { h -> shared(v, h) } }

    private fun shared(v: app.obsilearn.data.SavedVideo, h: app.obsi.core.data.Highlight) = app.obsi.core.share.SharedHighlight(
        app = app.obsi.core.ObsiApp.Learn, itemId = v.meta.id, itemTitle = v.meta.title, text = h.text, note = h.note,
        color = HighlightColors.getOrNull(h.color)?.ui ?: 0xFFF2C14E, at = h.startSec.toString(),
        place = v.meta.course.ifBlank { v.meta.author } + " · " + Transcripts.formatTime(h.startSec), created = h.createdAt.takeIf { it > 0 } ?: v.addedAt, tags = h.tags, key = h.id,
    )

    private fun seedFromBundledClipperSettings() {
        if (settings.value.templates.isNotEmpty()) return
        runCatching {
            val text = assets.open("default_clipper_settings.json").bufferedReader().use { it.readText() }
            settings.update { ClipperImport.apply(text, it) }
        }
    }
}
