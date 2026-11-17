package app.obsilearn.ui

import app.obsi.core.data.HttpWords
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.obsilearn.data.Drive
import app.obsilearn.data.Layout
import app.obsilearn.data.Source
import app.obsilearn.data.SourceErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Each kind of source: id, name, what it is for, and its icon. */
private data class Kind(val id: String, val title: String, val about: String, val icon: ImageVector)

private val kinds = listOf(
    Kind("folder", "Folder on this phone", "Also an SD card, a USB stick, or a cloud app that shows its folders", Icons.Rounded.Folder),
    Kind("gdrive", "Google Drive", "Sign in with Google and pick a folder", Icons.Rounded.Cloud),
    Kind("webdav", "NAS or cloud (WebDAV)", "Synology, QNAP, Nextcloud, ownCloud, pCloud, Koofr and more", Icons.Rounded.Storage),
    Kind("smb", "Windows or NAS share", "A shared folder on your Wi-Fi (SMB)", Icons.Rounded.Dns),
    Kind("hub", "Imperium PC hub", "The desktop hub on your PC", Icons.Rounded.Computer),
    Kind("web", "Web folder", "A web page that lists the files", Icons.Rounded.Language),
    Kind("feed", "Podcast or feed", "An RSS or Atom feed with audio or video", Icons.Rounded.Podcasts),
    Kind("links", "Video links", "Paste links to video or audio files", Icons.Rounded.Link),
)

fun kindName(id: String) = kinds.firstOrNull { it.id == id }?.title ?: id

/** "Add a course": pick where the videos are, then fill in what that place needs. */
@Composable
fun AddSourceSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun say(t: String) = android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_LONG).show()

    /** Saves the source, reads it, and closes when courses were found. */
    fun add(given: Source) {
        busy = true; error = null
        // The address says what it is. An smb:// address in the WebDAV form is a share, and the other way round.
        // Typed forms are tidied first: \\NAS\share, %20 in a share address, spaces in a web address.
        val s = SourceErrors.kindFor(SourceErrors.cleanAddress(given))
        var s0 = s
        if (s.kind != given.kind) say(if (s.kind == "smb") "That is a shared-folder (SMB) address, so ObsiLearn uses it as one." else "That is a web address, so ObsiLearn uses WebDAV.")
        app.courses.addSource(s)
        scope.launch {
            var r = runCatching { app.courses.scan(s.id) }
            // Many home servers speak plain http. An https address to one of them fails at once, so try http.
            val plain = SourceErrors.plainHttpFor(s.uri, r.exceptionOrNull())
            if (plain != null) {
                app.courses.editSource(s.id) { it.copy(uri = plain) }
                val again = runCatching { app.courses.scan(s.id) }
                if (again.isSuccess) say("That server uses http, not https. ObsiLearn changed the address.")
                r = again
            }
            // A NAS with WebDAV off often has the same folder as a shared folder. Try that before giving up.
            if (s.kind == "webdav" && SourceErrors.notWebDav(r.exceptionOrNull())) {
                SourceErrors.smbFor(s)?.let { smb ->
                    app.courses.removeSource(s.id)
                    app.courses.addSource(smb)
                    val again = runCatching { app.courses.scan(smb.id) }
                    if (again.isSuccess) { say("WebDAV is off on that NAS, so ObsiLearn opened the same folder as a shared folder (SMB)."); s0 = smb; r = again }
                    else app.courses.removeSource(smb.id)
                }
            }
            busy = false
            val s = s0
            r.onSuccess { n ->
                if (n == 0) { error = "No lessons found there. Check that it has video, audio or document files, and that you picked the course folder."; app.courses.removeSource(s.id) }
                else {
                    say(if (n == 1) "Added 1 course." else "Added $n courses.")
                    onDismiss()
                    // Folders can be read many ways: ask how you want this one shown.
                    if (s.kind !in setOf("hub", "links", "feed")) SetupNav.open(s.id)
                }
            }.onFailure { e -> error = SourceErrors.say(e); app.courses.removeSource(s.id) }
        }
    }
    fun newId(k: String) = k + "-" + System.currentTimeMillis().toString(36)

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { kind = null; return@rememberLauncherForActivityResult }
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, uri)?.name ?: "Folder"
        add(Source(newId("folder"), "folder", name, uri.toString(), addedAt = System.currentTimeMillis()))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kind != null && !busy) IconButton(onClick = { kind = null; error = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(if (kind == null) "Add a course" else kindName(kind!!), style = MaterialTheme.typography.titleLarge)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Reading the courses…")
            }
            when (kind) {
                null -> kinds.forEach { k ->
                    Surface(
                        onClick = { kind = k.id; if (k.id == "folder") folderPicker.launch(null) },
                        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(k.icon, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(k.title, style = MaterialTheme.typography.titleSmall)
                                Text(k.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                "folder" -> if (!busy) TextButton(onClick = { folderPicker.launch(null) }) { Text("Pick the folder") }
                "gdrive" -> DrivePicker(busy) { id, name -> add(Source(newId("gdrive"), "gdrive", name, id, addedAt = System.currentTimeMillis())) }
                "webdav" -> LoginForm(
                    busy, "Address", "https://nas.local:5006/Courses", userHint = "User name",
                ) { a, u, p -> add(Source(newId("webdav"), "webdav", nameOf(a), a.trim(), u.trim(), p, System.currentTimeMillis())) }
                "smb" -> LoginForm(
                    busy, "Share", "192.168.1.20/Courses", userHint = "User name (blank for guest)",
                ) { a, u, p -> add(Source(newId("smb"), "smb", nameOf(a), a.trim(), u.trim(), p, System.currentTimeMillis())) }
                "hub" -> LoginForm(
                    busy, "Hub address", "192.168.1.20:8787", userHint = null, secretHint = "Pairing code",
                ) { a, _, p -> add(Source(newId("hub"), "hub", "PC hub", a.trim(), "", p.trim(), System.currentTimeMillis())) }
                "web" -> LoginForm(busy, "Folder address", "https://example.com/course/", userHint = null, secretHint = null) { a, _, _ ->
                    add(Source(newId("web"), "web", nameOf(a), a.trim(), addedAt = System.currentTimeMillis()))
                }
                "feed" -> LoginForm(busy, "Feed address", "https://example.com/feed.xml", userHint = null, secretHint = null) { a, _, _ ->
                    add(Source(newId("feed"), "feed", nameOf(a), a.trim(), addedAt = System.currentTimeMillis()))
                }
                "links" -> {
                    var text by remember { mutableStateOf("") }
                    var name by remember { mutableStateOf("") }
                    OutlinedTextField(name, { name = it }, label = { Text("Course name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        text, { text = it }, label = { Text("Links, one on each line") }, minLines = 4,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    )
                    Button(onClick = {
                        add(Source(newId("links"), "links", name.ifBlank { "Links" }, text.trim(), addedAt = System.currentTimeMillis()))
                    }, enabled = !busy && text.contains("http")) { Text("Add") }
                }
            }
        }
    }
}

private fun nameOf(address: String): String =
    Uri.decode(address.trim().trimEnd('/').substringAfterLast('/')).ifBlank { address.trim() }

@Composable
private fun LoginForm(
    busy: Boolean,
    addressLabel: String,
    addressHint: String,
    userHint: String?,
    secretHint: String? = "Password",
    onAdd: (address: String, user: String, secret: String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    OutlinedTextField(
        address, { address = it }, label = { Text(addressLabel) }, placeholder = { Text(addressHint) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
    )
    if (userHint != null) OutlinedTextField(user, { user = it }, label = { Text(userHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (secretHint != null) OutlinedTextField(
        secret, { secret = it }, label = { Text(secretHint) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onAdd(address, user, secret) }, enabled = !busy && address.isNotBlank()) { Text("Add") }
}

/** Sign in with Google, then walk your Drive folders and pick one. */
@Composable
private fun DrivePicker(busy: Boolean, onPick: (id: String, name: String) -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf(listOf("root" to "My Drive")) }
    var folders by remember { mutableStateOf<List<Drive.File>?>(null) }
    val client = remember { com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(ctx) }
    val request = remember {
        com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(Drive.SCOPE))).build()
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching { client.getAuthorizationResultFromIntent(res.data) }
            .onSuccess { token = it.accessToken; if (token == null) error = "Google did not sign you in." }
            .onFailure { error = "Google did not sign you in." }
    }
    fun signIn() {
        error = null
        client.authorize(request)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build()) else token = r.accessToken
            }
            .addOnFailureListener { e -> error = SourceErrors.say(e) }
    }
    LaunchedEffect(Unit) { signIn() }
    LaunchedEffect(token, path) {
        val t = token ?: return@LaunchedEffect
        folders = null
        folders = runCatching { withContext(Dispatchers.IO) { Drive.children(app.courses.http, t, path.last().first).filter { it.folder } } }
            .onFailure { error = SourceErrors.say(it) }.getOrNull().orEmpty()
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    if (token == null) {
        Button(onClick = { signIn() }) { Text("Sign in with Google") }
        return
    }
    Text(path.joinToString("  /  ") { it.second }, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (path.size > 1) TextButton(onClick = { path = path.dropLast(1) }) { Text("Up") }
        Button(onClick = { onPick(path.last().first, if (path.size == 1) "My Drive" else path.last().second) }, enabled = !busy && path.size > 1) {
            Text("Use this folder")
        }
    }
    val list = folders
    if (list == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    else if (list.isEmpty()) Text("No folders in here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else list.sortedWith(compareBy(Layout.natural) { it.name }).forEach { f ->
        Surface(onClick = { path = path + (f.id to f.name) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, null)
                Spacer(Modifier.width(12.dp))
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Your sources: read again, set how courses are found, or take one off. */
@Composable
fun SourcesScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val data by app.courses.state.collectAsState()
    val scanning by app.courses.scanning.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Source?>(null) }
    fun say(t: String) = android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_SHORT).show()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, "Add a course") }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data.sources.isEmpty()) item { Text("No sources yet. Tap + to add a course.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(data.sources, key = { it.id }) { s ->
                val courses = data.courses.filter { it.sourceId == s.id }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            kindName(s.kind) + " · " + courses.size + (if (courses.size == 1) " course" else " courses") +
                                " · " + courses.sumOf { it.lessons.size } + " lessons",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val hidden = courses.filter { it.hidden }
                        if (hidden.isNotEmpty()) TextButton(onClick = { hidden.forEach { c -> app.courses.editCourse(c.id) { it.copy(hidden = false) } } }) {
                            Text("Show ${hidden.size} hidden course" + if (hidden.size == 1) "" else "s")
                        }
                        if (s.kind in setOf("folder", "webdav", "smb", "gdrive", "web")) Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("All one course", style = MaterialTheme.typography.bodyMedium)
                                Text("Off: each folder with modules in it is its own course.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = s.oneCourse, onCheckedChange = { v -> app.courses.editSource(s.id) { it.copy(oneCourse = v) }; app.rescan(s.id) { say(it) } })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { app.rescan(s.id) { say(it) } }, enabled = s.id !in scanning) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                                Text(if (s.id in scanning) "Reading…" else "Read again")
                            }
                            TextButton(onClick = { removing = s }) {
                                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove")
                            }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddSourceSheet(onDismiss = { adding = false })
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${s.name}?") },
            text = { Text("Its courses leave ObsiLearn. Your files stay where they are. Your highlights stay in the app and in your vault.") },
            confirmButton = { TextButton(onClick = { app.courses.removeSource(s.id); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}
