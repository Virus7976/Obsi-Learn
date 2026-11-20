package app.obsilearn.ui

import app.obsilearn.data.PhoneSpeech
import app.obsi.core.ai.Ai
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.obsi.core.ui.VaultNotesPage
import app.obsi.core.obsidian.VaultNotes
import app.obsilearn.data.LearnSettings
import app.obsilearn.data.Transcriber

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
internal fun SwitchLine(title: String, about: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            about?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** The course notes ObsiLearn keeps in your vault: one per course. */
@Composable
fun VaultNotesScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val l by app.learn.state.collectAsState()
    val data by app.courses.state.collectAsState()
    val byId = data.courses.associateBy { it.id }
    VaultNotesPage(
        onBack = onBack,
        title = "Vault notes",
        folder = l.notesFolder,
        idKey = "obsilearn",
        describe = { n -> if (n.type == "obsilearn-progress") byId[n.id]?.let { "Progress of ${it.title}" } ?: "Progress of a course you removed" else null },
    ) {
        Text(
            "One note per course, nothing more. Transcripts stay in the app. Highlights go to your vault only when you save them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchLine("Go on to the next lesson", "When a lesson ends, the next one starts. A reading lesson waits for you to open it.", l.autoNext) { v -> app.learn.update { it.copy(autoNext = v) } }
        SwitchLine("Course notes", "Keep each course's progress in your vault, so it syncs.", l.progressNotes) { v -> app.learn.update { it.copy(progressNotes = v) } }
        OutlinedTextField(
            l.notesFolder, { v -> app.learn.update { it.copy(notesFolder = v.trim('/').ifBlank { "ObsiLearn" }) } },
            label = { Text("Folder") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        SwitchLine(
            "Keep out of search",
            "Adds the folder to Obsidian's Excluded files. The notes stay in the file list.",
            l.hideFromSearch,
        ) { v ->
            val ok = VaultNotes.setExcluded(ctx, app.settings.value, l.notesFolder, v)
            if (ok) app.learn.update { it.copy(hideFromSearch = v) }
            else android.widget.Toast.makeText(ctx, "Could not change Obsidian's settings. Is the vault folder picked?", android.widget.Toast.LENGTH_LONG).show()
        }
        Text(
            "In search you can also leave them out with -tag:#obsilearn. In Bases, filter on type is not obsilearn-progress.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** ObsiLearn's own settings: transcripts, offline copies, and the pages for sources and vault notes. */
@Composable
fun LearnSettingsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val l by app.learn.state.collectAsState()
    val model by app.transcriber.modelProgress.collectAsState()
    val saved by app.offline.saved.collectAsState()
    fun set(block: (LearnSettings) -> LearnSettings) = app.learn.update(block)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Courses and transcripts", style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card("Sources") {
                Text("Where your courses are.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = nav.openSources) { Text("Open sources") }
            }
            Card("Vault notes") {
                Text("One progress note per course, in your vault.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = nav.openVaultNotes) { Text("Open vault notes") }
            }
            Card("Transcripts") {
                val aiNow by androidx.compose.runtime.remember { Ai.store(app) }.state.collectAsState()
                val cloud = if (l.engineChosen) l.engine == "cloud" else aiNow.speech != null
                Text("For lessons with no subtitle file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!cloud, { set { it.copy(engine = "phone", engineChosen = true) } }, label = { Text("On this phone") })
                    FilterChip(cloud, { set { it.copy(engine = "cloud", engineChosen = true) } }, label = { Text("With your key") })
                }
                if (!cloud) {
                    Text("Free, offline and private. Slower than a service.", style = MaterialTheme.typography.bodySmall)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val best = androidx.compose.runtime.remember { PhoneSpeech.suggest(ctx) }
                    val now = PhoneSpeech.pick(ctx, l.phoneModel)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        PhoneSpeech.Model.entries.forEach { m ->
                            FilterChip(now == m, { set { it.copy(phoneModel = m.key) } }, label = { Text("${m.title}, ${m.mb} MB") })
                        }
                    }
                    Text(now.about + if (now == best) " Suits this phone." else "", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (app.transcriber.modelReady(now)) "This model is on the phone." else "It is fetched the first time you make a transcript. Use Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    model?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                } else {
                    Text("Fast and very exact. You pay the service.", style = MaterialTheme.typography.bodySmall)
                    val ai by androidx.compose.runtime.remember { Ai.store(app) }.state.collectAsState()
                    val sp = ai.speech
                    Text(
                        if (sp != null) "Uses ${sp.title} (${sp.speechModel}), from Settings › AI service."
                        else "Set it up in Settings › AI service: add an OpenRouter, Groq or OpenAI key, then pick it under Transcripts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sp != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        l.language, { v -> set { it.copy(language = v.trim().lowercase()) } }, label = { Text("Language (blank finds it)") },
                        placeholder = { Text("en") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Hidden until a text model works, like every AI part of the app.
                val textAi by Ai.usable.collectAsState()
                if (textAi) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Clean up with AI", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Fixes misheard words, stops and capitals. Sends only the text, not the sound.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(l.cleanUp, { v -> set { it.copy(cleanUp = v) } })
                }
            }
            Card("Offline") {
                Text(
                    "${saved.size} lesson" + (if (saved.size == 1) "" else "s") + " on this phone, " + app.offline.totalMb().let { if (it < 1) "under 1 MB." else "%.0f MB.".format(it) },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (saved.isNotEmpty()) TextButton(onClick = { saved.keys.toList().forEach { app.offline.remove(it) } }) { Text("Remove all offline copies") }
            }
        }
    }
}
