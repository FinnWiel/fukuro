package fukuro

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/* ---------------- Login ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(vm: ShelfViewModel, onBack: () -> Unit = {}, onLoggedIn: () -> Unit) {
    val state by vm.state.collectAsState()
    val savedServer by vm.store.serverFlow.collectAsState(initial = null)
    val savedUser by vm.store.usernameFlow.collectAsState(initial = null)
    var addr by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("13378") }
    var https by remember { mutableStateOf(false) }
    var prefilled by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    // signed in already: the form stays folded away until asked for
    var changing by remember { mutableStateOf(false) }

    // prefill from the last server used on this device (nothing hardcoded)
    LaunchedEffect(savedServer) {
        val s = savedServer
        if (!prefilled && !s.isNullOrBlank()) {
            val scheme = if (s.contains("://")) s.substringBefore("://") else "http"
            val rest = s.substringAfter("://").trimEnd('/')
            val host = rest.substringBefore('/')
            val hasPort = host.contains(':') && !host.startsWith("[") // ignore bare IPv6
            val h = if (hasPort) host.substringBeforeLast(':') else host
            https = scheme == "https"
            addr = h
            port = if (hasPort) host.substringAfterLast(':') else ""
            prefilled = true
        }
    }

    val serverUrl = buildServerUrl(addr, port, https)
    val signedIn = state.loggedIn && !savedServer.isNullOrBlank()

    Scaffold(topBar = {
        TopAppBar(
            title = { },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        )
    }) { pad ->
    Column(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Fukuro", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            if (signedIn) "Your Audiobookshelf server" else "Connect to your Audiobookshelf server",
            style = MaterialTheme.typography.bodyMedium
        )

        if (signedIn) {
            Spacer(Modifier.height(20.dp))
            // A saved server is a saved server whether or not it answers right now, so
            // this says what is stored and reports reachability separately instead of
            // dropping the user back to an empty form when they are offline.
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.serverOnline) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (state.serverOnline) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            state.serverOnline -> "Connected"
                            state.serverChecked -> "Signed in, but the server isn't answering"
                            else -> "Signed in"
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    savedServer.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                savedUser?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "as $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!state.serverOnline && state.serverChecked) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Downloads and books on this phone still play. The rest of the " +
                            "library comes back when the server does.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onBack) { Text("Done") }
                    OutlinedButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        Text(if (state.loading) "Checking…" else "Retry")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { changing = !changing }) {
                    Text(if (changing) "Cancel" else "Use a different server")
                }
                TextButton(onClick = { vm.logout(); changing = true }) { Text("Log out") }
            }
        }

        // the form itself: always there when signed out, on request when signed in
        if (signedIn && !changing) {
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        Spacer(Modifier.height(24.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // some servers sit behind a proxy that only speaks https, and typing the
            // scheme into the address field is easy to get wrong
            FilterChip(
                selected = !https,
                onClick = {
                    https = false
                    if (port == "443") port = "13378"
                },
                label = { Text("http") }
            )
            FilterChip(
                selected = https,
                onClick = {
                    https = true
                    if (port == "13378") port = "443"
                },
                label = { Text("https") }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                addr, { addr = it }, singleLine = true,
                label = { Text("Server address") },
                placeholder = { Text("192.168.1.10") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, singleLine = true,
                label = { Text("Port") },
                placeholder = { Text(if (https) "443" else "13378") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(110.dp)
            )
        }
        if (serverUrl.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                serverUrl, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            user, { user = it }, label = { Text("Username") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth()
                // tells the Android Autofill framework (1Password etc.) this is a username field
                .semantics { contentType = ContentType.Username }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
                .semantics { contentType = ContentType.Password }
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.login(serverUrl, user, pass) { ok -> if (ok) onLoggedIn() } },
            enabled = !state.loading && serverUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.loading) "Connecting…" else "Log in") }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            "No server? You can use Fukuro with books stored on this phone and sign in later from Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { vm.continueOffline { onLoggedIn() } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue without a server") }
    }
    }
}

/**
 * Combines the address and port fields into a base URL.
 * Accepts a bare IP/hostname, or a full URL that may already carry a scheme,
 * a port of its own, or a subpath (reverse proxies). A scheme typed into the
 * address wins over [https], which is only the fallback for a bare host.
 */
fun buildServerUrl(addr: String, port: String, https: Boolean = false): String {
    val trimmed = addr.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    val withScheme =
        if (trimmed.contains("://")) trimmed else "${if (https) "https" else "http"}://$trimmed"
    val scheme = withScheme.substringBefore("://")
    val rest = withScheme.substringAfter("://")
    val host = rest.substringBefore('/')
    val path = rest.removePrefix(host)
    // a port typed into the address field wins; IPv6 literals are left alone
    val hostHasPort = host.contains(':') && !host.startsWith("[")
    if (hostHasPort || port.isBlank()) return "$scheme://$host$path"
    return "$scheme://$host:${port.trim()}$path"
}

/**
 * Actions for one book. Opened from the player's ⋮ and by long-pressing any cover.
 * [onResetSeek] lets the player rewind itself when progress is wiped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookOptionsSheet(
    vm: ShelfViewModel,
    itemId: String,
    onDismiss: () -> Unit,
    onResetSeek: () -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val dlStates by vm.downloadStates.collectAsState()
    val book = state.items.firstOrNull { it.id == itemId }
    val title = book?.media?.metadata?.title ?: ""
    val p = state.progress[itemId]
    val isFav = itemId in state.favorites
    val isDownloaded = itemId in state.downloadedIds
    val downloading = dlStates[itemId] != null
    var showRename by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var renameText by remember(title) { mutableStateOf(title) }
    var renameError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            Text(
                title.ifBlank { "Book" }, style = MaterialTheme.typography.titleLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(12.dp))

            SheetRow(
                icon = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                label = if (isFav) "Remove from favorites" else "Add to favorites",
                slashed = isFav
            ) { vm.toggleFavorite(itemId) }

            when {
                downloading -> SheetRow(Icons.Rounded.Download, "Downloading…") {}
                isDownloaded -> SheetRow(
                    icon = Icons.Rounded.Download,
                    label = "Remove download",
                    slashed = true
                ) { confirmRemove = true }
                else -> SheetRow(Icons.Rounded.Download, "Download for offline") {
                    vm.download(itemId); onDismiss()
                }
            }

            SheetRow(Icons.Rounded.AddToHomeScreen, "Add to home screen") {
                vm.pinBookShortcut(itemId); onDismiss()
            }
            SheetRow(Icons.Rounded.Edit, "Rename") { showRename = true }
            SheetRow(
                if (p?.isFinished == true) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll,
                if (p?.isFinished == true) "Mark unfinished" else "Mark finished"
            ) {
                vm.markFinished(itemId, !(p?.isFinished ?: false)); onDismiss()
            }
            SheetRow(Icons.Rounded.RestartAlt, "Reset progress") {
                onResetSeek()
                vm.resetProgress(itemId)
                onDismiss()
            }
            // only worth offering while the book is actually on that shelf
            if (p != null && !p.isFinished && p.progress > 0.001) {
                val hidden = itemId in state.continueHidden
                SheetRow(
                    icon = Icons.Rounded.PlaylistRemove,
                    label = if (hidden) "Back to Continue Listening" else "Remove from Continue Listening",
                    slashed = false
                ) {
                    if (hidden) vm.unhideFromContinue(itemId) else vm.hideFromContinue(itemId)
                    onDismiss()
                }
            }
        }
    }

    if (confirmRemove) {
        val mb = remember(itemId) { vm.downloads.sizeOnDisk(itemId) / 1_000_000 }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove download?") },
            text = { Text("Deletes the offline copy and frees $mb MB. The book stays on your server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false; vm.deleteDownload(itemId); onDismiss()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename book") },
            text = {
                Column {
                    OutlinedTextField(renameText, { renameText = it }, singleLine = true,
                        label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                    renameError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameBook(itemId, renameText) { err ->
                        if (err == null) { showRename = false; onDismiss() } else renameError = err
                    }
                }, enabled = renameText.isNotBlank()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    slashed: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        if (slashed) SlashedIcon(icon, null, tint)
        else Icon(icon, null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** An icon with a diagonal line through it, for the "undo" side of an action. */
@Composable
fun SlashedIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier.size(24.dp),
) {
    val cutColor = MaterialTheme.colorScheme.surface
    Box(modifier) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val pad = size.minDimension * 0.08f
            val start = Offset(pad, size.height - pad)
            val end = Offset(size.width - pad, pad)
            // wide cut in the surface colour first, so the slash reads against the glyph
            drawLine(cutColor, start, end, strokeWidth = size.minDimension * 0.26f, cap = StrokeCap.Round)
            drawLine(tint, start, end, strokeWidth = size.minDimension * 0.11f, cap = StrokeCap.Round)
        }
    }
}

/**
 * A book's authors as separate names. Audiobookshelf stores co-authors in one
 * string ("Neil Gaiman, Terry Pratchett"), so matching the whole string against a
 * single author found nothing and the book vanished from both author pages.
 */
fun authorsOf(item: LibraryItem): List<String> =
    (item.media.metadata.authorName ?: "")
        .split(',', ';', '&')
        .map { it.trim() }
        .filter { it.isNotBlank() }

fun LibraryItem.hasAuthor(name: String): Boolean =
    authorsOf(this).any { it.equals(name.trim(), ignoreCase = true) }

/** Same treatment for narrators, which Audiobookshelf also joins into one string. */
fun narratorsOf(item: LibraryItem): List<String> =
    (item.media.metadata.narratorName ?: "")
        .split(',', ';', '&')
        .map { it.trim() }
        .filter { it.isNotBlank() }

fun LibraryItem.hasNarrator(name: String): Boolean =
    narratorsOf(this).any { it.equals(name.trim(), ignoreCase = true) }

/** Cover size options: home carousel width, and how many columns the grids use. */
val COVER_SIZE_LABELS = listOf("XS", "S", "M", "L", "XL")
private val COVER_ROW_WIDTHS = listOf(88, 104, 120, 142, 168)
private val COVER_GRID_COLUMNS = listOf(5, 4, 3, 2, 1)

fun coverRowWidth(size: Int) = COVER_ROW_WIDTHS[size.coerceIn(0, 4)]
fun coverGridColumns(size: Int) = COVER_GRID_COLUMNS[size.coerceIn(0, 4)]

/* ---------------- Home ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: ShelfViewModel,
    onOpenBook: (String) -> Unit,
    onOpenServer: () -> Unit = {},
    onOpenNarrator: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val sectionsCsv by vm.store.homeSectionsFlow.collectAsState(initial = Store.DEFAULT_SECTIONS)
    val customShelf by vm.store.customShelfFlow.collectAsState(initial = emptyList())

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Icon(
                    painter = painterResource(R.drawable.ic_owl),
                    contentDescription = "Fukuro",
                    tint = Color(0xFFEF4223),
                    modifier = Modifier.width(52.dp).height(32.dp),
                )
            },
            actions = {
                // server status; tap to add a server or manage the connection
                val tint = when {
                    state.serverOnline -> Color(0xFF2FBF71)
                    state.loggedIn -> Color(0xFFE5484D)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onOpenServer() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(
                        if (state.serverOnline) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                        contentDescription = "Server connection",
                        modifier = Modifier.size(18.dp),
                        tint = tint
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            state.serverOnline -> "Server"
                            state.loggedIn -> "Offline"
                            else -> "Add server"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = tint
                    )
                }
            }
        )
    }) { pad ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { UpdateBanner(vm) }
            val sections = sectionsCsv.split(',').filter { it.isNotBlank() }
            sections.forEach { section ->
                when (section) {
                    "continue" -> {
                        // most recently listened first; dismissed books are left out
                        val inProgress = vm.continueListening()
                        if (inProgress.isNotEmpty()) {
                            item { SectionHeader("Continue Listening") }
                            item { BookRow(vm, inProgress, state, onOpenBook) }
                        }
                    }
                    "series" -> {
                        // offline a series shrinks to the part of it that's on the device,
                        // and drops out entirely when none of it is
                        val withBooks = state.series
                            .map { s ->
                                if (state.offline) s.copy(books = s.books.filter { state.isOnDevice(it.id) }) else s
                            }
                            .filter { it.books.isNotEmpty() }
                        if (withBooks.isNotEmpty()) {
                            item { SectionHeader("Series") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    items(withBooks, key = { it.id }) { series ->
                                        CollectionCard(
                                            // up to four covers, in reading order
                                            covers = series.books.take(4).map { vm.coverModel(it.id) },
                                            title = series.name,
                                            subtitle = "${series.books.size} book" +
                                                (if (series.books.size == 1) "" else "s"),
                                            coverSize = state.coverSize,
                                            onClick = { onOpenSeries(series.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "favorites" -> {
                        val favs = state.items.filter { it.id in state.favorites }
                        if (favs.isNotEmpty()) {
                            item { SectionHeader("Favorites") }
                            item { BookRow(vm, favs, state, onOpenBook) }
                        }
                    }
                    "completed" -> {
                        // most recently finished first, so the shelf reflects what you just ended
                        val done = state.items
                            .filter { state.progress[it.id]?.isFinished == true }
                            .sortedByDescending { state.progress[it.id]?.lastUpdate ?: 0L }
                        if (done.isNotEmpty()) {
                            item { SectionHeader("Completed") }
                            item { BookRow(vm, done, state, onOpenBook) }
                        }
                    }
                    "downloaded" -> {
                        val downloaded = state.items.filter { it.id in state.downloadedIds }
                        if (downloaded.isNotEmpty()) {
                            item { SectionHeader("Downloaded") }
                            item { BookRow(vm, downloaded, state, onOpenBook) }
                        }
                    }
                    "custom" -> {
                        val visible = customShelf.filter { entry ->
                            when (entry.type) {
                                "book" -> state.items.any { it.id == entry.id }
                                "series" -> state.series.firstOrNull { it.id == entry.id }
                                    ?.books?.any { !state.offline || state.isOnDevice(it.id) } == true
                                "author" -> state.items.any { it.hasAuthor(entry.id) }
                                "narrator" -> state.items.any { it.hasNarrator(entry.id) }
                                else -> false
                            }
                        }
                        if (visible.isNotEmpty()) {
                            item { SectionHeader("Custom") }
                            item {
                                CustomShelfRow(
                                    vm = vm,
                                    entries = visible,
                                    state = state,
                                    onOpenBook = onOpenBook,
                                    onOpenSeries = onOpenSeries,
                                    onOpenAuthor = onOpenAuthor,
                                    onOpenNarrator = onOpenNarrator,
                                )
                            }
                        }
                    }
                    "authors" -> {
                        // prefer the server's author list (it carries portraits); fall back to
                        // grouping books by author name when the endpoint isn't available
                        val authors = state.authors.ifEmpty {
                            // split co-authors so each gets their own card
                            state.items.flatMap { authorsOf(it) }
                                .groupingBy { it }.eachCount()
                                .map { (name, count) -> AbsAuthor(id = "", name = name, numBooks = count) }
                                .sortedBy { it.name.lowercase() }
                        }
                            // offline, an author whose books are all on the server is a dead end
                            .filter { a -> !state.offline || state.items.any { it.hasAuthor(a.name) } }
                        if (authors.isNotEmpty()) {
                            item { SectionHeader("Authors") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    items(authors, key = { it.id.ifBlank { it.name } }) { author ->
                                        val books = state.items.count { it.hasAuthor(author.name) }
                                        CollectionCard(
                                            covers = listOf(author.imagePath?.let { vm.api.authorImageUrl(author.id) }),
                                            title = author.name,
                                            subtitle = (if (books > 0) books else author.numBooks).let {
                                                "$it book" + (if (it == 1) "" else "s")
                                            },
                                            coverSize = state.coverSize,
                                            onClick = { onOpenAuthor(author.name) },
                                            placeholder = { OwlPlaceholder() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "narrators" -> {
                        val narrators = state.items.flatMap { narratorsOf(it) }
                            .groupingBy { it }.eachCount()
                            .toList().sortedBy { it.first.lowercase() }
                        if (narrators.isNotEmpty()) {
                            item { SectionHeader("Narrators") }
                            item {
                                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    items(narrators, key = { it.first }) { (name, count) ->
                                        CollectionCard(
                                            // the server has no narrator portraits, so the
                                            // owl stands in, same as an author without a photo
                                            covers = listOf(null),
                                            title = name,
                                            subtitle = "$count book" + (if (count == 1) "" else "s"),
                                            coverSize = state.coverSize,
                                            onClick = { onOpenNarrator(name) },
                                            placeholder = { OwlPlaceholder() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "all" -> {
                        item { SectionHeader("All Books") }
                        item { BookRow(vm, state.items.sortedBy { it.media.metadata.titleIgnorePrefix ?: it.media.metadata.title }, state, onOpenBook) }
                    }
                }
            }
            item { Spacer(Modifier.height(140.dp)) } // clear the floating chrome
        }
        }
    }
}

/**
 * Quiet strip at the top of Home when a newer release is out — otherwise the automatic
 * check would only ever be visible to someone who went looking in Settings.
 */
@Composable
private fun UpdateBanner(vm: ShelfViewModel) {
    val u by vm.update.collectAsState()
    val info = u.info
    if (info == null || u.dismissed) return

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.height(52.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when {
                    u.downloading -> "Downloading ${info.version}… ${(u.progress * 100).toInt()}%"
                    u.needsPermission -> "Allow Fukuro to install apps"
                    else -> "Fukuro ${info.version} is available"
                },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (u.downloading) {
                CircularProgressIndicator(
                    progress = { u.progress },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
            } else {
                TextButton(onClick = {
                    when {
                        u.needsPermission -> vm.grantInstallPermission()
                        u.file != null -> vm.installUpdate()
                        else -> vm.downloadUpdate()
                    }
                }) {
                    Text(
                        when {
                            u.needsPermission -> "Allow"
                            u.file != null -> "Install"
                            else -> "Update"
                        }
                    )
                }
            }
            IconButton(onClick = { vm.dismissUpdate() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/* ---------------- Library (full grid + search) ---------------- */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    vm: ShelfViewModel,
    onOpenBook: (String) -> Unit,
    miniPlayerVisible: Boolean = false,
) {
    // the floating chrome is taller when the mini player is showing
    val chromeHeight = if (miniPlayerVisible) 140.dp else 84.dp
    val state by vm.state.collectAsState()
    val favoritesTop by vm.store.favoritesTopFlow.collectAsState(initial = false)
    var query by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("title") }
    var filterBy by remember { mutableStateOf("all") }
    var descending by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()

    val searched = if (query.isBlank()) state.items else state.items.filter {
        (it.media.metadata.title ?: "").contains(query, ignoreCase = true) ||
            (it.media.metadata.authorName ?: "").contains(query, ignoreCase = true) ||
            (it.media.metadata.narratorName ?: "").contains(query, ignoreCase = true) ||
            (it.media.metadata.seriesName ?: "").contains(query, ignoreCase = true)
    }
    val filtered = searched.filter { item ->
        val p = state.progress[item.id]
        when (filterBy) {
            "reading" -> p != null && !p.isFinished && p.progress > 0.001
            "unstarted" -> p == null || (p.progress <= 0.001 && !p.isFinished)
            "finished" -> p?.isFinished == true
            "downloaded" -> item.id in state.downloadedIds
            "favorites" -> item.id in state.favorites
            else -> true
        }
    }
    val base = when (sortBy) {
        "author" -> filtered.sortedBy { it.media.metadata.authorName?.lowercase() ?: "￿" }
        "narrator" -> filtered.sortedBy { it.media.metadata.narratorName?.lowercase() ?: "￿" } // no narrator -> last
        "duration" -> filtered.sortedBy { it.media.duration }
        "added" -> filtered.sortedByDescending { it.addedAt }
        "progress" -> filtered.sortedByDescending { state.progress[it.id]?.progress ?: 0.0 }
        else -> filtered.sortedBy { (it.media.metadata.titleIgnorePrefix ?: it.media.metadata.title ?: "").lowercase() }
    }
    val ordered = if (descending) base.reversed() else base
    // favourites float to the top when the setting is on
    val sorted = if (favoritesTop) ordered.sortedByDescending { it.id in state.favorites } else ordered

    val activeFilters = (if (filterBy != "all") 1 else 0) + (if (sortBy != "title") 1 else 0)

    Scaffold(topBar = { TopAppBar(title = { Text("Library") }) }) { pad ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(coverGridColumns(state.coverSize)),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = chromeHeight),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // search scrolls away with the content
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                // hand-built so the border can match the icon stroke weight;
                                // OutlinedTextField hard-codes a 1dp border
                                Box(
                                    Modifier.weight(1f).height(48.dp)
                                        .border(
                                            2.dp,
                                            MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(24.dp)
                                        )
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        BasicTextField(
                                            value = query,
                                            onValueChange = { query = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            modifier = Modifier.weight(1f),
                                            decorationBox = { inner ->
                                                if (query.isEmpty()) {
                                                    Text(
                                                        "Search",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                inner()
                                            }
                                        )
                                        Icon(
                                            Icons.Filled.Search, null, Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { sheetOpen = true }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Rounded.FilterList, "Sort and filter",
                                        tint = if (activeFilters > 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (activeFilters > 0 || query.isNotBlank()) {
                                Text(
                                    "${sorted.size} of ${state.items.size} books",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    items(sorted, key = { it.id }) { book -> BookGridCell(vm, book, state, onOpenBook) }
                }

                // back to top, once the search bar is well out of sight
                val showTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 6 } }
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTop,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    // sits just above the mini player, or in its place when there isn't one
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 16.dp, bottom = if (miniPlayerVisible) 124.dp else 66.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { gridState.animateScrollToItem(0) } }
                    ) { Icon(Icons.Rounded.KeyboardArrowUp, "Back to top") }
                }
            }
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort & filter", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { sortBy = "title"; filterBy = "all"; descending = false }) {
                        Text("Reset")
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Sort by", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "title" to "Title", "author" to "Author", "narrator" to "Narrator",
                        "added" to "Recently added", "duration" to "Length", "progress" to "Progress",
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = sortBy == key,
                            onClick = { sortBy = key },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilterChip(
                    selected = descending,
                    onClick = { descending = !descending },
                    label = { Text(if (descending) "Descending" else "Ascending") },
                    leadingIcon = {
                        Icon(
                            if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                            null, Modifier.size(16.dp)
                        )
                    }
                )

                Spacer(Modifier.height(20.dp))
                Text("Show", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "all" to "All books", "reading" to "In progress", "unstarted" to "Not started",
                        "finished" to "Finished", "downloaded" to "Downloaded", "favorites" to "Favorites",
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = filterBy == key,
                            onClick = { filterBy = key },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Favorites on top", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Applies everywhere, saved as a setting",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = favoritesTop,
                        onCheckedChange = { on -> scope.launch { vm.store.setFavoritesTop(on) } }
                    )
                }
            }
        }
    }
}

/** Card for a series or author in a carousel: cover, name, count. */
@Composable
private fun CollectionCard(
    covers: List<Any?>, // URLs for server items, Files for on-device covers
    title: String,
    subtitle: String,
    coverSize: Int,
    onClick: () -> Unit,
    placeholder: @Composable () -> Unit = { CoverPlaceholder() },
) {
    // series/author cards track the cover size setting, a little wider than book cards
    Column(Modifier.width((coverRowWidth(coverSize) + 20).dp).padding(4.dp).clickable { onClick() }) {
        val shape = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
        if (covers.size > 1) {
            CoverMosaic(covers, contentDescription = title, modifier = shape)
        } else {
            CoverImage(
                model = covers.firstOrNull(), contentDescription = title,
                modifier = shape, placeholder = placeholder
            )
        }
        Text(
            title, style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Finished badge: same outer chip and inner diameter as [CoverProgressRing], but the
 * ring is a filled accent disc with a white tick drawn at the track's stroke width.
 */
@Composable
private fun CoverFinishedBadge(boxScope: androidx.compose.foundation.layout.BoxScope) {
    val accent = MaterialTheme.colorScheme.primary
    with(boxScope) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp)
                .clip(CircleShape).background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.minDimension
                drawCircle(accent, radius = w / 2f)
                val tick = Path().apply {
                    moveTo(w * 0.33f, w * 0.52f)
                    lineTo(w * 0.45f, w * 0.65f)
                    lineTo(w * 0.68f, w * 0.37f)
                }
                drawPath(
                    tick, Color.White,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

/** Progress as a bar across the bottom edge of the cover. */
@Composable
private fun CoverProgressBar(progress: Float, boxScope: androidx.compose.foundation.layout.BoxScope) {
    with(boxScope) {
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // follow the cover's rounded bottom corners
                .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .height(5.dp).background(Color(0x99000000))
        ) {
            Box(
                Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/** Draws whichever progress indicator the user picked, or the finished badge. */
@Composable
private fun CoverProgressOverlay(
    p: MediaProgress?,
    style: String,
    boxScope: androidx.compose.foundation.layout.BoxScope,
) {
    when {
        p?.isFinished == true -> CoverFinishedBadge(boxScope)
        p != null && p.progress > 0.001 ->
            if (style == "bar") CoverProgressBar(p.progress.toFloat(), boxScope)
            else CoverProgressRing(p.progress.toFloat(), boxScope)
    }
}

/** Small circular progress ring overlaid on a cover's bottom-right corner. */
@Composable
private fun CoverProgressRing(progress: Float, boxScope: androidx.compose.foundation.layout.BoxScope) {
    with(boxScope) {
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp)
                .clip(CircleShape).background(Color(0xB3000000)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(18.dp),
                strokeWidth = 3.dp,
                trackColor = Color(0x8CFFFFFF),
                gapSize = 0.dp
            )
        }
    }
}

/** One book cell in a grid (Library, series page, author page). */
@Composable
fun BookGridCell(vm: ShelfViewModel, book: LibraryItem, state: UiState, onOpenBook: (String) -> Unit) {
    val p = state.progress[book.id]
    var showOptions by remember { mutableStateOf(false) }
    if (showOptions) BookOptionsSheet(vm, book.id, onDismiss = { showOptions = false })
    Column(
        Modifier.padding(4.dp).combinedClickable(
            onClick = { onOpenBook(book.id) },
            onLongClick = { showOptions = true }
        )
    ) {
        Box(Modifier.fillMaxWidth()) {
            CoverImage(
                model = vm.coverModel(book.id),
                contentDescription = book.media.metadata.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
            )
            CoverProgressOverlay(p, state.progressStyle, this)
        }
        Text(
            book.media.metadata.title ?: "?",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Grid page for one series. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesGridScreen(vm: ShelfViewModel, seriesId: String, onOpenBook: (String) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val dlStates by vm.downloadStates.collectAsState()
    val series = state.series.firstOrNull { it.id == seriesId } ?: return
    // offline the page shows only the books that can actually be opened
    val books = if (state.offline) series.books.filter { state.isOnDevice(it.id) } else series.books
    val ids = books.map { it.id }
    val allIds = series.books.map { it.id }
    val downloadedIds = allIds.filter { it in state.downloadedIds }
    val downloadedCount = downloadedIds.size
    val allFavorite = allIds.isNotEmpty() && allIds.all { it in state.favorites }
    val busy = ids.any { dlStates.containsKey(it) }
    var showRemoveDownloads by remember(seriesId) { mutableStateOf(false) }
    var selectedForRemoval by remember(seriesId) { mutableStateOf<Set<String>>(emptySet()) }

    if (showRemoveDownloads) {
        val allSelected = downloadedIds.isNotEmpty() && downloadedIds.all { it in selectedForRemoval }
        AlertDialog(
            onDismissRequest = { showRemoveDownloads = false },
            title = { Text("Remove series downloads?") },
            text = {
                Column {
                    Text("Choose which offline books to remove. The books stay on your server.")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selectedForRemoval = if (allSelected) emptySet() else downloadedIds.toSet()
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allSelected,
                            onCheckedChange = {
                                selectedForRemoval = if (it) downloadedIds.toSet() else emptySet()
                            }
                        )
                        Text("Select all (${downloadedIds.size})")
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(series.books.filter { it.id in state.downloadedIds }, key = { it.id }) { book ->
                            val checked = book.id in selectedForRemoval
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selectedForRemoval = if (checked) selectedForRemoval - book.id
                                    else selectedForRemoval + book.id
                                }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        selectedForRemoval = if (on) selectedForRemoval + book.id
                                        else selectedForRemoval - book.id
                                    }
                                )
                                Text(
                                    book.media.metadata.title ?: book.relPath,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedForRemoval.isNotEmpty(),
                    onClick = {
                        vm.deleteAll(selectedForRemoval.toList())
                        showRemoveDownloads = false
                    }
                ) { Text("Remove (${selectedForRemoval.size})") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDownloads = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(series.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("$downloadedCount/${ids.size}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(12.dp))
                    }
                    else -> {
                        FavoriteHeart(
                            favorite = allFavorite,
                            onToggle = { vm.toggleSeriesFavorite(allIds) },
                            tint = if (allFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = { vm.pinSeriesShortcut(seriesId) }) {
                            Icon(Icons.Rounded.AddToHomeScreen, "Add series to home screen")
                        }
                        if (downloadedCount < ids.size) {
                            IconButton(onClick = { vm.downloadAll(ids) }) {
                                Icon(Icons.Rounded.Download, "Download whole series")
                            }
                        }
                        if (downloadedCount > 0) {
                            IconButton(onClick = {
                                selectedForRemoval = downloadedIds.toSet()
                                showRemoveDownloads = true
                            }) {
                                Icon(Icons.Rounded.Delete, "Delete downloaded books in series")
                            }
                        }
                    }
                }
            }
        )
    }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(coverGridColumns(state.coverSize)),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 140.dp),
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
            items(books, key = { it.id }) { book -> BookGridCell(vm, book, state, onOpenBook) }
        }
    }
}

/** Grid page for one narrator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NarratorGridScreen(vm: ShelfViewModel, narrator: String, onOpenBook: (String) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val books = state.items.filter { it.hasNarrator(narrator) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(narrator, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(coverGridColumns(state.coverSize)),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 140.dp),
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
            items(books, key = { it.id }) { book -> BookGridCell(vm, book, state, onOpenBook) }
        }
    }
}

/** Grid page for one author. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorGridScreen(vm: ShelfViewModel, author: String, onOpenBook: (String) -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val books = state.items.filter { it.hasAuthor(author) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(coverGridColumns(state.coverSize)),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 140.dp),
            modifier = Modifier.fillMaxSize().padding(pad)
        ) {
            items(books, key = { it.id }) { book -> BookGridCell(vm, book, state, onOpenBook) }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun BookRow(vm: ShelfViewModel, books: List<LibraryItem>, state: UiState, onOpenBook: (String) -> Unit) {
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
        items(books, key = { it.id }) { book ->
            BookShelfCard(vm, book, state, onOpenBook)
        }
    }
}

@Composable
private fun BookShelfCard(
    vm: ShelfViewModel,
    book: LibraryItem,
    state: UiState,
    onOpenBook: (String) -> Unit,
) {
    val p = state.progress[book.id]
    var showOptions by remember { mutableStateOf(false) }
    if (showOptions) BookOptionsSheet(vm, book.id, onDismiss = { showOptions = false })
    Column(
        Modifier.width(coverRowWidth(state.coverSize).dp).padding(4.dp)
            .combinedClickable(
                onClick = { onOpenBook(book.id) },
                onLongClick = { showOptions = true },
            )
    ) {
        Box(Modifier.fillMaxWidth()) {
            CoverImage(
                model = vm.coverModel(book.id),
                contentDescription = book.media.metadata.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
            )
            CoverProgressOverlay(p, state.progressStyle, this)
        }
        Text(
            book.media.metadata.title ?: "?",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A single ordered row that deliberately keeps unlike library entities mixed. */
@Composable
private fun CustomShelfRow(
    vm: ShelfViewModel,
    entries: List<CustomShelfEntry>,
    state: UiState,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenNarrator: (String) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
        items(entries, key = { "${it.type}:${it.id}" }) { entry ->
            when (entry.type) {
                "book" -> state.items.firstOrNull { it.id == entry.id }?.let {
                    BookShelfCard(vm, it, state, onOpenBook)
                }
                "series" -> state.series.firstOrNull { it.id == entry.id }?.let { series ->
                    val books = if (state.offline) {
                        series.books.filter { state.isOnDevice(it.id) }
                    } else series.books
                    if (books.isNotEmpty()) {
                        CollectionCard(
                            covers = books.take(4).map { vm.coverModel(it.id) },
                            title = entry.title,
                            subtitle = "${books.size} book" + if (books.size == 1) "" else "s",
                            coverSize = state.coverSize,
                            onClick = { onOpenSeries(entry.id) },
                        )
                    }
                }
                "author" -> {
                    val books = state.items.filter { it.hasAuthor(entry.id) }
                    if (books.isNotEmpty()) {
                        val author = state.authors.firstOrNull { it.name.equals(entry.id, ignoreCase = true) }
                        CollectionCard(
                            covers = listOf(author?.imagePath?.let { vm.api.authorImageUrl(author.id) }),
                            title = entry.title,
                            subtitle = "${books.size} book" + if (books.size == 1) "" else "s",
                            coverSize = state.coverSize,
                            onClick = { onOpenAuthor(entry.id) },
                            placeholder = { OwlPlaceholder() },
                        )
                    }
                }
                "narrator" -> {
                    val count = state.items.count { it.hasNarrator(entry.id) }
                    if (count > 0) {
                        CollectionCard(
                            covers = listOf(null),
                            title = entry.title,
                            subtitle = "$count book" + if (count == 1) "" else "s",
                            coverSize = state.coverSize,
                            onClick = { onOpenNarrator(entry.id) },
                            placeholder = { OwlPlaceholder() },
                        )
                    }
                }
            }
        }
    }
}

