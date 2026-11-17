package app.obsilearn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import app.obsi.core.obsidian.VaultNotes
import app.obsi.core.ui.OnboardingScreen
import app.obsi.core.ui.OnboardingStep
import app.obsi.core.ui.Onboarding as ObsiOnboardingHelp

/** ObsiLearn's first run: the shared welcome and vault steps, then where course progress goes. */
@Composable
fun LearnOnboarding(onDone: () -> Unit) {
    val app = LocalApp.current
    OnboardingScreen(
        pitch = "Watch your video courses from any folder, NAS or drive. Highlight what the teacher says, and keep your progress in Obsidian.",
        steps = listOf(
            OnboardingStep("Course progress") { ProgressStep() },
            OnboardingStep("Your courses") {
                Text(
                    "Next, add a course. Pick a folder on this phone, a NAS share, Google Drive, a web folder or a feed. ObsiLearn reads the folders and shows you how it will set them up.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Lesson files like PDFs, Word files and slides open inside the app.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ),
        onDone = onDone,
    )
}

@Composable
private fun ProgressStep() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val l by app.learn.state.collectAsState()
    val s by app.settings.state.collectAsState()
    val scheme = MaterialTheme.colorScheme
    Text(
        "ObsiLearn keeps one note per course in your vault: which lessons are done, where you stopped, and each round you watch it. Tick a lesson in Obsidian and the app sees it.",
        style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant,
    )
    SwitchLine("Course notes", "Keep each course's progress in your vault.", l.progressNotes) { v -> app.learn.update { it.copy(progressNotes = v) } }
    if (l.progressNotes) {
        OutlinedTextField(
            l.notesFolder, { v -> app.learn.update { it.copy(notesFolder = v.trim('/').ifBlank { "ObsiLearn" }) } },
            label = { Text("Folder in your vault") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        val vault = ObsiOnboardingHelp.folderName(s)
        Surface(shape = RoundedCornerShape(14.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("The notes go here", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(
                    (if (vault.isBlank()) "(no vault yet)" else vault) + " / " + l.notesFolder + " / Course name.md",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        SwitchLine("Keep out of search", "Adds the folder to Obsidian's Excluded files.", l.hideFromSearch) { v ->
            val ok = VaultNotes.setExcluded(ctx, app.settings.value, l.notesFolder, v)
            if (ok) app.learn.update { it.copy(hideFromSearch = v) }
            else android.widget.Toast.makeText(ctx, "Pick your vault folder first.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
