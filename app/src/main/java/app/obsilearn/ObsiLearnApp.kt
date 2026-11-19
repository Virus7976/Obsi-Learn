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
    /** The page and view of each lesson file, so it opens where you stopped. */
    lateinit var docPlaces: app.obsilearn.data.DocPlaces
    /** The words of transcripts and files, for search. */
    lateinit var textIndex: app.obsilearn.data.TextIndex
        private set
    lateinit var reviews: app.obsilearn.data.Reviews
    /** Each watch-through of a course. */
    lateinit var rounds: app.obsilearn.data.Rounds
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
        app.obsi.core.share.SharedStyle.source = { app.obsi.core.share.ObsiStyle.of(settings.value) }
        app.obsi.core.share.SharedReview.due = { reviews.due(library.videos.value).map { shared(it.video, it.highlight) } }
        app.obsi.core.share.SharedReview.act = { key, action -> reviews.act(key, action) }
        app.obsi.core.share.SharedSearch.source = { q ->
            val videos = library.videos.value
            val titled = videos.associateBy { it.meta.id }
            val inside = runCatching { textIndex.search(q, videos, perItem = 2, limit = 20) }.getOrDefault(emptyList()).mapNotNull { f ->
                val v = titled[f.id] ?: return@mapNotNull null
                app.obsi.core.share.SharedHit(app.obsi.core.ObsiApp.Learn, "text", v.meta.title, placeOf(f.id, f.at), f.snippet, f.id, f.at.toString(), 30.0)
            }
            app.obsilearn.data.LibrarySearch.search(q, videos, playlists.all.value, place = { v, s -> placeOf(v.meta.id, s) }).mapNotNull { h ->
                when (h.kind) { app.obsilearn.data.HitKind.Video -> hitFor(h, "item"); app.obsilearn.data.HitKind.Highlight -> hitFor(h, "highlight"); else -> null }
            } + inside
        }
        settings = SettingsStore(this)
        vaults = VaultStore(this) { settings.value.vaultFolders }
        updates = SelfUpdate(this, settings)
        library = Library(this)
        reviews = app.obsilearn.data.Reviews(this)
        rounds = app.obsilearn.data.Rounds(this)
        playlists = Playlists(this)
        queue = PlayQueue(settings)
        learn = app.obsilearn.data.LearnSettingsStore(this)
        courses = app.obsilearn.data.CourseStore(this)
        offline = app.obsilearn.data.Offline(this, courses)
        offline.fetchFile = { sid, uri -> localFile(sid, uri) }
        docPlaces = app.obsilearn.data.DocPlaces(this)
        textIndex = app.obsilearn.data.TextIndex(java.io.File(filesDir, "text-index"), { library.loadTranscript(it) }, { library.transcriptStamp(it) })
        // The decoder for transcripts reads shares through the app's share reader.
        app.obsi.core.speech.Pcm.customSource = { u -> if (u.scheme == "smb") app.obsilearn.data.Smb.media(courses.sourceFor(u.toString()), u.toString()) else null }
        transcriber = app.obsilearn.data.Transcriber(this, courses, offline, library) { learn.value }
        player = PlayerHost(this, app.obsilearn.data.LessonStreams(courses) { offline }) { settings.value }
        player.artworkFor = { id -> library.get(id)?.meta?.image?.let { img -> if (img.startsWith("hub")) courses.find(id)?.let { courses.playable(it.first.sourceId, img).first } else img } }
        // Every lesson has a place in the library, so search, highlights and review work for all of them.
        appScope.launch { courses.state.collect { library.sync(lessonMetas()) } }
        // Only a show or course you removed takes its lessons out. A refresh never does.
        courses.onRemoved = { ids -> appScope.launch { dropRemoved(ids) } }

        // A lesson saved while it plays goes on from the saved copy.
        appScope.launch {
            offline.saved.collect { saved ->
                val id = player.state.value.videoId ?: return@collect
                if (id in saved && offline.localStreams(id) != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player.moveToSaved(id) }
            }
        }

        // Time spent, for the shared stats: counted while something plays, in the app or in the background.
        appScope.launch {
            val log = app.obsi.core.data.Activity.log(this@ObsiLearnApp)
            while (true) { kotlinx.coroutines.delay(15_000); player.state.value.let { p -> if (p.playing) log.addTime(15, p.title) } }
        }
        // Long saves and transcripts keep running with the app in the background.
        appScope.launch {
            kotlinx.coroutines.flow.combine(offline.progress, transcriber.progress) { a, b -> a.size + b.size }.distinctUntilChanged().collect { n ->
                WorkService.update(this@ObsiLearnApp, n)
            }
        }
        watchProgressNotes()
        player.onEnded = {
            val ended = player.state.value.videoId
            ended?.let { finished(it); autoClip(it) }
            // Your own queue first; then the course goes on by itself.
            if (queue.ids.value.isNotEmpty()) playNextInQueue() else ended?.let { playNextInCourse(it) }
        }
        player.onWatched = { finished(it); autoClip(it) }
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

    /**
     * A lesson whose show or course you removed leaves the library, so search and lists stop finding it.
     * A lesson with something you made (highlights, tags, a pin, a playlist) stays.
     */
    private suspend fun dropRemoved(removed: Set<String>) {
        val inPlaylist = playlists.all.value.flatMapTo(HashSet()) { it.videoIds }
        val gone = library.videos.value.filter { v ->
            v.meta.id in removed && courses.find(v.meta.id) == null &&
                v.highlights.isEmpty() && v.tags.isEmpty() && !v.pinned && v.playlistId.isEmpty() && v.meta.id !in inPlaylist
        }
        if (gone.isEmpty()) return
        // The player must be stopped on the main thread.
        val ids = gone.mapTo(HashSet()) { it.meta.id }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (player.state.value.videoId in ids) player.stop()
            queue.ids.value.filter { it in ids }.forEach { queue.remove(it) }
            ids.forEach { offline.remove(it) }
            library.removeAll(ids)
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
            kotlinx.coroutines.flow.combine(library.videos, player.state.map { it.playing }.distinctUntilChanged(), learn.state, rounds.state) { v: List<app.obsilearn.data.SavedVideo>, playing: Boolean, l: app.obsilearn.data.LearnSettings, _: Any -> Triple(v, playing, l) }
                .debounce(2500L)
                .collect { (videos, playing, l) ->
                    if (!l.progressNotes) return@collect
                    val byId = videos.associateBy { it.meta.id }
                    for (c in courses.courses) {
                        val sig = c.lessons.joinToString(",") { les ->
                            val v = byId[les.id]
                            (if (v?.done == true) "x" else "") + ((v?.positionSec ?: 0.0) / 10).toInt() + "/" + (v?.highlights?.size ?: 0)
                        } + c.title + "/r" + rounds.of(c.id).let { it.current.toString() + ":" + it.watches.values.sumOf { w -> w.size } }
                        if (written[c.id] == sig) continue
                        // While it plays, only a lesson that just got done is worth a write.
                        val before = written[c.id]
                        if (playing && before != null && before.count { it == 'x' } == sig.count { it == 'x' }) continue
                        if (app.obsilearn.data.ProgressNotes.write(this@ObsiLearnApp, settings.value, l.notesFolder, c, byId, rounds.of(c.id))) written[c.id] = sig
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
                // Rounds: an older note's ticks belong to a round that is over. A newer one means another phone started again.
                val noteRound = app.obsilearn.data.ProgressNotes.round(text)
                val here = rounds.of(c.id).current
                if (noteRound < here) continue
                if (noteRound > here) {
                    rounds.catchUp(c.id, noteRound, c.lessons.count { byId[it.id]?.done == true }, c.lessons.size)
                    c.lessons.forEach { les -> library.edit(les.id) { it.copy(doneAt = 0L, positionSec = 0.0) } }
                    continue
                }
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
        return androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", localFile(sourceId, uri))
    }

    /** The file on this phone, fetched from its source once and kept in the cache. A saved name keeps its type. */
    fun localFile(sourceId: String, uri: String): java.io.File {
        val dir = java.io.File(cacheDir, "open").also { it.mkdirs() }
        val raw = if (uri.startsWith("content:")) (runCatching {
            contentResolver.query(android.net.Uri.parse(uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.substringAfterLast("%2F")) else uri.substringAfterLast('/').substringBefore('?')
        val name = android.net.Uri.decode(raw).replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
        val f = java.io.File(dir, app.obsilearn.data.Layout.id(uri).take(8) + "-" + name)
        // A file saved for offline is used first, so a lesson's files open with no network.
        java.io.File(offline.filesDir, f.name).takeIf { it.length() > 0 }?.let { return it }
        if (uri.startsWith("file:")) return java.io.File(android.net.Uri.parse(uri).path.orEmpty())
        if (!f.exists() || f.length() == 0L) {
            val input = if (uri.startsWith("content:")) contentResolver.openInputStream(android.net.Uri.parse(uri)) ?: error("The file is gone.")
            else courses.open(sourceId, uri)
            val tmp = java.io.File(dir, f.name + ".part")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
        }
        return f
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

    /** The picture for a lesson, or a lesson's file: its course's cover, kept the same way as for the course card. */
    fun lessonArt(id: String): Any? = (courses.find(id) ?: library.get(id)?.meta?.parent?.let { courses.find(it) })?.first?.let { coverModel(it) }

    /** The id of a lesson's file, as an item of its own. Made from the lesson and the file, so it never changes. */
    fun fileId(lessonId: String, uri: String) = "f" + app.obsilearn.data.Layout.id(lessonId, uri)

    /**
     * Makes sure a lesson's file has its own item, so its highlights, place and words are kept.
     * Returns the item id. Nothing is made for a file with no lesson.
     */
    fun fileItem(lessonId: String, uri: String, title: String, ext: String): String? {
        val (c, m, l) = courses.find(lessonId) ?: return null
        val id = fileId(lessonId, uri)
        if (library.get(id) == null) library.sync(listOf(app.obsilearn.data.VideoMeta(
            id = id, title = title.ifBlank { l.title }, author = c.title, description = l.title,
            link = "obsilearn://file/$id", image = c.cover, course = c.title, module = m.title,
            parent = lessonId, file = uri, ext = ext,
        )))
        return id
    }

    /** Where a highlight is, for people: a time in a lesson, or how far in for a file. */
    fun placeOf(id: String, at: Double): String {
        val meta = library.get(id)?.meta
        if (meta?.isFile == true) {
            val pct = if (meta.words > 0) (at * 100 / meta.words).toInt().coerceIn(0, 100) else -1
            return when { pct < 0 -> meta.ext.uppercase(); pct < 1 -> "Start of the " + meta.ext.uppercase(); else -> "$pct% in" }
        }
        return Transcripts.formatTime(at)
    }

    private val _indexing = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Courses whose files are being read for search, 0..1. */
    val indexing: kotlinx.coroutines.flow.StateFlow<Map<String, Float>> = _indexing

    /**
     * Reads every file of a course once, so search finds words inside them before you open them.
     * Files already read are skipped. A file that cannot be read is skipped too.
     */
    fun indexFiles(courseId: String, done: (String) -> Unit) {
        val c = courses.course(courseId) ?: return
        if (courseId in _indexing.value) return
        val files = c.lessons.flatMap { l -> (listOf(Triple(l.page, l.title, "html")).filter { it.first.isNotBlank() } + l.resources.map { Triple(it.uri, it.title, it.ext) }).map { l to it } }
        if (files.isEmpty()) { done("This course has no files to read."); return }
        _indexing.value = _indexing.value + (courseId to 0f)
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var ok = 0
            files.forEachIndexed { i, (l, f) ->
                val (uri, title, ext) = f
                runCatching {
                    val id = fileItem(l.id, uri, title, ext) ?: return@runCatching
                    if (textIndex.has(id)) { ok++; return@runCatching }
                    val local = localFile(c.sourceId, uri)
                    val out = app.obsilearn.data.DocText.build(local, ext) ?: return@runCatching
                    val built = app.obsilearn.data.DocText.article(out.html, title)
                    if (built.doc.words.isEmpty()) return@runCatching
                    textIndex.putFile(id, built.doc.words.map { w -> built.doc.text.substring(w.charStart, w.charEnd) })
                    library.edit(id) { it.copy(meta = it.meta.copy(words = built.doc.words.size)) }
                    ok++
                }
                _indexing.value = _indexing.value + (courseId to (i + 1f) / files.size)
            }
            _indexing.value = _indexing.value - courseId
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                done(if (ok == files.size) "All ${files.size} files can be searched now." else "$ok of ${files.size} files can be searched now. The rest have no text that can be read.")
            }
        }
    }

    /** What went wrong, in words a person can act on. */
    fun explain(e: Throwable?): String = e?.let { app.obsilearn.data.SourceErrors.say(it) } ?: "Something went wrong. Try again."

    /** The lesson after this one in its course, or null at the end. */
    fun nextLesson(id: String): app.obsilearn.data.Lesson? {
        val c = courses.find(id)?.first ?: return null
        val all = c.lessons
        val i = all.indexOfFirst { it.id == id }
        return if (i < 0) null else all.getOrNull(i + 1)
    }

    /** A reading lesson that is next after the lesson that just ended. The lesson screen offers to open it. */
    val nextReading = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Plays the next lesson in the course when the one playing ends, or points at the next reading lesson. */
    private fun playNextInCourse(endedId: String) {
        if (!learn.value.autoNext) return
        val n = nextLesson(endedId) ?: return
        if (n.media.isBlank()) { nextReading.value = n.id; return }
        val v = library.get(n.id) ?: return
        player.load(n.id, v.meta.title, v.meta.author, v.positionSec.takeIf { !v.done } ?: 0.0, force = true)
        advanced.tryEmit(n.id)
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

    /** A lesson was watched or read to the end: done, and one more watch in its course's history. */
    fun finished(id: String) {
        markWatched(id)
        courses.find(id)?.first?.let { rounds.recordWatch(it.id, id) }
    }

    /** Starts the course again: every lesson not done and back at the start. The round before is kept. */
    fun startNewRound(courseId: String) {
        val c = courses.courses.firstOrNull { it.id == courseId } ?: return
        val done = c.lessons.count { library.get(it.id)?.done == true }
        rounds.startNew(courseId, done, c.lessons.size)
        val now = System.currentTimeMillis()
        c.lessons.forEach { l -> library.edit(l.id) { it.copy(doneAt = 0L, positionSec = 0.0, playedAt = now) } }
    }

    /** A video was watched to the end. It counts as done, and leaves every playlist that is set to drop watched videos. */
    fun markWatched(id: String) {
        if (library.get(id)?.done == false) app.obsi.core.data.Activity.log(this).finished()
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
            // Starts where you stopped, like opening it yourself.
            player.load(id, v.meta.title, v.meta.author, v.resumeSec.takeIf { !v.done } ?: 0.0, force = true)
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

    /** This app's answer when another Obsi app searches. */
    private fun hitFor(h: app.obsilearn.data.Hit, kind: String) = app.obsi.core.share.SharedHit(
        app = app.obsi.core.ObsiApp.Learn, kind = kind, title = h.title, subtitle = h.subtitle, snippet = h.snippet.orEmpty(),
        itemId = h.videoId, at = if (kind == "item") "" else h.sec.toString(), score = h.score,
        color = if (kind == "highlight") app.obsi.core.data.HighlightColors.getOrNull(h.color)?.ui ?: 0 else 0,
    )

    private fun sharedHighlights(): List<app.obsi.core.share.SharedHighlight> =
        library.videos.value.flatMap { v -> v.highlights.map { h -> shared(v, h) } }

    private fun shared(v: app.obsilearn.data.SavedVideo, h: app.obsi.core.data.Highlight) = app.obsi.core.share.SharedHighlight(
        app = app.obsi.core.ObsiApp.Learn, itemId = v.meta.id, itemTitle = v.meta.title, text = h.text, note = h.note,
        color = HighlightColors.getOrNull(h.color)?.ui ?: 0xFFF2C14E, at = h.startSec.toString(),
        place = v.meta.course.ifBlank { v.meta.author } + " · " + placeOf(v.meta.id, h.startSec), created = h.createdAt.takeIf { it > 0 } ?: v.addedAt, tags = h.tags, key = h.id,
    )

    private fun seedFromBundledClipperSettings() {
        if (settings.value.templates.isNotEmpty()) return
        runCatching {
            val text = assets.open("default_clipper_settings.json").bufferedReader().use { it.readText() }
            settings.update { ClipperImport.apply(text, it) }
        }
    }
}
