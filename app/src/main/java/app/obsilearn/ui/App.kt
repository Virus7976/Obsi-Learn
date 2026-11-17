package app.obsilearn.ui

import androidx.compose.runtime.LaunchedEffect
import app.obsi.core.ui.Onboarding as ObsiOnboarding

import app.obsi.core.data.Transcripts

import app.obsi.core.*
import app.obsi.core.data.*
import app.obsi.core.obsidian.*
import app.obsi.core.ui.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Description
import app.obsi.core.update.*
import app.obsi.core.link.*
import app.obsi.core.share.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.obsilearn.ObsiLearnApp
import kotlinx.coroutines.launch

val LocalApp = staticCompositionLocalOf<ObsiLearnApp> { error("ObsiLearnApp not provided") }

/** Ways to move around that any screen can use. */
class Nav(
    val openVideo: (String) -> Unit,
    val openCourse: (String) -> Unit = {},
    /** Opens the course a lesson is in. */
    val openCourseOf: (String) -> Unit = {},
    val openSources: () -> Unit = {},
    val openVaultNotes: () -> Unit = {},
    val openLearnSettings: () -> Unit = {},
    val openReview: () -> Unit = {},
    val openHighlights: () -> Unit = {},
    val openStats: () -> Unit = {},
)

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav not provided") }

sealed interface Screen {
    data object Home : Screen
    data class Reader(val id: String) : Screen
    data class Course(val id: String) : Screen
    data object Sources : Screen
    data object VaultNotes : Screen
    data object LearnSettings : Screen
    data object Settings : Screen
    data object Review : Screen
    data object Highlights : Screen
    data object Stats : Screen
    data class Template(val id: String) : Screen
    data class Doc(val req: DocRequest) : Screen
    data class Setup(val sourceId: String) : Screen
    data object Welcome : Screen
}

@Composable
fun AppRoot(app: ObsiLearnApp, incomingLink: String?, onLinkConsumed: () -> Unit) {
    val settings by app.settings.state.collectAsState()
    MarginaliaTheme(settings) {
        CompositionLocalProvider(LocalApp provides app) {
            // First run: the walk-through sits on top of home until it is done.
            val stack = remember { mutableStateListOf<Screen>(Screen.Home).also { if (ObsiOnboarding.needed(app.settings.value)) it.add(Screen.Welcome) } }
            // Home stays underneath. A second Back in the same frame must not take it away too.
            fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            BackHandler(enabled = stack.size > 1 && stack.last() != Screen.Welcome) { pop() }
            // Each screen keeps its place while another one is on top: open tab, scroll, search box.
            val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            fun toast(text: String) = android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()

            // The reader on top is replaced by the next video, so Back still goes home.
            fun pushReader(id: String) {
                val top = stack.last()
                if (top is Screen.Reader) { if (top.id != id) stack[stack.lastIndex] = Screen.Reader(id) }
                // Going on from a reading lesson to a video: the video takes its place, so back goes to the course.
                else if (top is Screen.Doc && top.req.lessonId != id && app.courses.find(top.req.lessonId)?.third?.media?.isBlank() == true) stack[stack.lastIndex] = Screen.Reader(id)
                else stack.add(Screen.Reader(id))
            }
            fun pushCourse(id: String) {
                val top = stack.last()
                if (!(top is Screen.Course && top.id == id)) stack.add(Screen.Course(id))
            }
            LaunchedEffect(Unit) {
                DocNav.requests.collect { r ->
                    if (r == null) return@collect
                    val top = stack.last()
                    // Another lesson's file takes the place of this one. A file of the same lesson (or from a zip) opens on top.
                    if (top is Screen.Doc && top.req.lessonId != r.lessonId) stack[stack.lastIndex] = Screen.Doc(r)
                    else if (!(top is Screen.Doc && top.req == r)) stack.add(Screen.Doc(r))
                    DocNav.requests.value = null
                }
            }
            LaunchedEffect(Unit) {
                SetupNav.requests.collect { id -> if (id != null) { stack.add(Screen.Setup(id)); SetupNav.requests.value = null } }
            }
            val nav = remember {
                Nav(
                    openVideo = { id ->
                        val l = app.courses.find(id)?.third
                        val fileMeta = app.library.get(id)?.meta?.takeIf { it.isFile }
                        when {
                            fileMeta != null -> openFile(ctx, app, fileMeta.parent, fileMeta.file, fileMeta.title, fileMeta.ext)
                            l == null -> toast("That lesson is not in your courses any more.")
                            l.media.isBlank() -> if (l.page.isNotBlank()) openFile(ctx, app, id, l.page, l.title, "html")
                                else openFile(ctx, app, id, l.resources.firstOrNull()?.uri.orEmpty(), l.title, l.resources.firstOrNull()?.ext.orEmpty())
                            app.library.get(id) != null -> pushReader(id)
                            else -> toast("That lesson is still being added. Try again in a moment.")
                        }
                    },
                    openCourse = { id -> pushCourse(id) },
                    openCourseOf = { id -> app.courses.find(id)?.first?.let { pushCourse(it.id) } },
                    openSources = { if (stack.last() != Screen.Sources) stack.add(Screen.Sources) },
                    openVaultNotes = { if (stack.last() != Screen.VaultNotes) stack.add(Screen.VaultNotes) },
                    openLearnSettings = { if (stack.last() != Screen.LearnSettings) stack.add(Screen.LearnSettings) },
                    openReview = { if (stack.last() != Screen.Review) stack.add(Screen.Review) },
                    openHighlights = { if (stack.last() != Screen.Highlights) stack.add(Screen.Highlights) },
                    openStats = { if (stack.last() != Screen.Stats) stack.add(Screen.Stats) },
                )
            }

            // A video link or file shared here. It becomes a lesson in "Links" and opens.
            var addLink by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(incomingLink) {
                val link = incomingLink ?: return@LaunchedEffect
                onLinkConsumed()
                val id = app.addLink(link)
                kotlinx.coroutines.delay(300)
                nav.openVideo(id)
            }
            // A video another Obsi app asked to open.
            val openItem by app.openItem.collectAsState()
            androidx.compose.runtime.LaunchedEffect(openItem) {
                val (id, at) = openItem ?: return@LaunchedEffect
                app.openItem.value = null
                if (at >= 0) app.pendingSeek.value = id to at
                nav.openVideo(id)
            }
            CompositionLocalProvider(LocalNav provides nav) {
            // The daily reminder opens Review.
            val wantReview by app.openReview.collectAsState()
            androidx.compose.runtime.LaunchedEffect(wantReview) {
                if (wantReview) { app.openReview.value = false; nav.openReview() }
            }
            // The media notification opens the video that is playing.
            val wantPlaying by app.openNowPlaying.collectAsState()
            androidx.compose.runtime.LaunchedEffect(wantPlaying) {
                if (wantPlaying) { app.openNowPlaying.value = false; app.player.state.value.videoId?.let(nav.openVideo) }
            }
            // Auto-save found a new note with empty fields: ask for them here, over any screen.
            val askSave by app.askSave.collectAsState()
            askSave?.let { id ->
                val video = app.library.get(id)
                if (video == null) app.askSave.value = null
                else {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val lines = androidx.compose.runtime.remember(id) {
                        app.library.loadTranscript(id)?.let {
                            Transcripts.toLines(it.cues, app.settings.value.lineLength, video.chapters)
                        }.orEmpty()
                    }
                    SaveSheet(
                        video = video,
                        lines = lines,
                        onDismiss = { app.askSave.value = null },
                        onDone = { message, ok ->
                            app.askSave.value = null
                            if (ok) app.library.edit(id) { it.copy(clippedAt = System.currentTimeMillis()) }
                            android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
            // When the queue starts the next video, the open reader follows it.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                app.advanced.collect { id ->
                    if (stack.last() is Screen.Reader) stack[stack.lastIndex] = Screen.Reader(id)
                }
            }
            val spring = LocalSpring.current

            // Self-healing: when a new release is found (for example a playback fix), say so right away.
            val update by app.updates.state.collectAsState()
            var later by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            (update as? app.obsi.core.update.UpdateState.Available)?.info?.let { info ->
                if (later != info.version) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { later = info.version },
                        title = { androidx.compose.material3.Text("Update ready: ${info.version}") },
                        text = {
                            androidx.compose.material3.Text(
                                info.notes.trim().ifBlank { "A new version is ready." }.take(500),
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { later = info.version; app.updates.installFlow(info) }) {
                                androidx.compose.material3.Text("Install")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { later = info.version }) {
                                androidx.compose.material3.Text("Later")
                            }
                        },
                    )
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AnimatedContent(
                    targetState = stack.last(),
                    // Like an iPhone: a new screen slides in over the old one from the right edge, and the old one
                    // drifts a little to the left under it. Back is the same in reverse. Nothing scales and nothing
                    // fades out from under you, so the page you are looking at stays solid the whole way.
                    transitionSpec = {
                        val deeper = depth(targetState) >= depth(initialState)
                        val move = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(380, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f))
                        if (deeper) {
                            (slideInHorizontally(move) { it } togetherWith slideOutHorizontally(move) { -it / 4 })
                                .apply { targetContentZIndex = 1f }
                        } else {
                            (slideInHorizontally(move) { -it / 4 } togetherWith slideOutHorizontally(move) { it })
                                .apply { targetContentZIndex = -1f }
                        }
                    },
                    label = "screens",
                ) { screen ->
                    // Each screen is solid, so the one underneath never shows through while they slide.
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    saved.SaveableStateProvider(screen.toString()) {
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            incoming = addLink,
                            onConsumed = { addLink = null },
                            onOpen = nav.openVideo,
                            onSettings = { stack.add(Screen.Settings) },
                        )
                        // The reader also goes back by itself when its video is removed, so it checks that it is on top.
                        is Screen.Reader -> ReaderScreen(screen.id, onBack = { if (stack.last() == screen) pop() })
                        is Screen.Course -> CourseScreen(screen.id, onBack = { if (stack.last() == screen) pop() })
                        Screen.Sources -> SourcesScreen(onBack = { pop() })
                        Screen.VaultNotes -> VaultNotesScreen(onBack = { pop() })
                        Screen.LearnSettings -> LearnSettingsScreen(onBack = { pop() })
                        Screen.Settings -> SettingsScreen(
                            onBack = { pop() },
                            onEditTemplate = { stack.add(Screen.Template(it)) },
                            appPages = listOf(
                                SettingsEntry("learn", "Courses and transcripts", "Sources, transcripts, offline", Icons.Rounded.School),
                                SettingsEntry("vaultnotes", "Vault notes", "Course progress notes in your vault", Icons.Rounded.Description),
                            ),
                            onAppPage = { id -> stack.add(if (id == "learn") Screen.LearnSettings else Screen.VaultNotes) },
                        )
                        is Screen.Template -> TemplateEditorScreen(screen.id, onBack = { pop() })
                        is Screen.Doc -> DocScreen(screen.req, onBack = { if (stack.last() == screen) pop() })
                        is Screen.Setup -> SetupScreen(screen.sourceId, onDone = { if (stack.last() == screen) pop() })
                        Screen.Welcome -> LearnOnboarding(onDone = {
                            stack.remove(Screen.Welcome)
                            if (app.courses.state.value.sources.isEmpty()) stack.add(Screen.Sources)
                        })
                        Screen.Review -> ReviewScreen(onBack = { pop() })
                        Screen.Highlights -> HighlightsScreen(onBack = { pop() })
                        Screen.Stats -> StatsScreen(onBack = { pop() })
                    }
                    }
                    }
                }
            }
            }
        }
    }
}

private fun depth(s: Screen) = when (s) {
    Screen.Home -> 0
    is Screen.Reader -> 1
    is Screen.Course -> 1
    Screen.Sources -> 2
    Screen.VaultNotes -> 2
    Screen.LearnSettings -> 2
    Screen.Settings -> 1
    Screen.Review -> 1
    Screen.Highlights -> 1
    Screen.Stats -> 1
    is Screen.Template -> 2
    is Screen.Doc -> 2
    is Screen.Setup -> 2
    Screen.Welcome -> 3
}

/** Opens a page or document of a lesson. ObsiLearn shows it itself when it can; other types go to another app. */
internal fun openFile(ctx: android.content.Context, app: ObsiLearnApp, lessonId: String, uri: String, title: String = "", type: String = "") {
    if (uri.isBlank()) { android.widget.Toast.makeText(ctx, "This lesson has nothing to open.", android.widget.Toast.LENGTH_SHORT).show(); return }
    val name = title.ifBlank { android.net.Uri.decode(uri.substringBefore('?').substringAfterLast('/').substringAfterLast("%2F")).substringBeforeLast('.') }
    // Always inside the app. The viewer has a button to another app for types it cannot show.
    DocNav.requests.value = DocRequest(lessonId, uri, name, type)
}
