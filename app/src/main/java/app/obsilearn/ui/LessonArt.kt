package app.obsilearn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * A lesson's picture: its course's cover. Covers on a NAS need a login, so they come from the saved copy
 * the course card uses. A course with no cover gets a quiet tile with a cap, never an empty black box.
 */
@Composable
fun LessonArt(id: String?, modifier: Modifier, alpha: Float = 1f) {
    val app = LocalApp.current
    // A cover that has just been saved bumps the course, so the picture shows without a restart.
    val courses by app.courses.state.collectAsState()
    val model = remember(id, courses) { id?.let { app.lessonArt(it) } }
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (model == null) Icon(Icons.Rounded.School, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant.copy(alpha = 0.55f * alpha))
        else AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = alpha)
    }
}
