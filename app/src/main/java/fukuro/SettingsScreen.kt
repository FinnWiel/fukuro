package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.compose.material3.TextButton
import java.io.File
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Update check, sitting right under the version it compares against.
 *
 * The app can notice a release and fetch it, but Android reserves the install itself for
 * the system installer, so the last step is always a confirmation the user taps.
 */
@Composable
private fun UpdatesSection(vm: ShelfViewModel) {
    val u by vm.update.collectAsState()
    val auto by vm.store.autoUpdateFlow.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    val info = u.info

    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = auto, onCheckedChange = { on -> scope.launch { vm.store.setAutoUpdate(on) } })
        Text("Check for updates on start", Modifier.weight(1f))
    }

    if (info != null) {
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp)
        ) {
            Text(
                "Fukuro ${info.version} is available",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "${info.sizeBytes / 1_000_000} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (info.notes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    info.notes.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                u.downloading -> {
                    Text(
                        "Downloading ${(u.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                    ) {
                        Box(
                            Modifier.fillMaxWidth(u.progress.coerceIn(0f, 1f)).height(4.dp)
                                .clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                // the permission is a one-off: granted once, later updates skip straight
                // to the installer
                u.needsPermission -> {
                    Text(
                        "Android needs your permission to let Fukuro install apps. " +
                            "Allow it, then tap Install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.grantInstallPermission() }) { Text("Allow") }
                        OutlinedButton(onClick = { vm.installUpdate() }) { Text("Install") }
                    }
                }
                u.file != null -> Button(onClick = { vm.installUpdate() }) { Text("Install") }
                else -> Button(onClick = { vm.downloadUpdate() }) { Text("Download and install") }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { vm.checkForUpdate(manual = true) },
            enabled = !u.checking && !u.downloading
        ) { Text(if (u.checking) "Checking…" else "Check for updates") }
        when {
            u.upToDate -> Text(
                "Up to date", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            u.error != null -> Text(
                u.error ?: "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Turns a SAF tree uri into something a human can recognise. */
private fun prettyFolder(uri: String): String {
    val decoded = java.net.URLDecoder.decode(uri, "UTF-8")
    val tail = decoded.substringAfterLast("/tree/").substringAfterLast(':')
    return if (tail.isBlank()) decoded.takeLast(40) else "…/$tail"
}

/** Hue / saturation / lightness picker for a custom accent colour. */
@Composable
private fun AccentPickerDialog(initial: Color, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val startHsl = remember(initial) {
        FloatArray(3).also { ColorUtils.colorToHSL(initial.toArgb(), it) }
    }
    var hue by remember { mutableFloatStateOf(startHsl[0]) }
    var sat by remember { mutableFloatStateOf(startHsl[1]) }
    var light by remember { mutableFloatStateOf(startHsl[2].coerceIn(0.25f, 0.65f)) }
    val picked = Color(ColorUtils.HSLToColor(floatArrayOf(hue, sat, light)))
    val hex = String.format("#%06X", 0xFFFFFF and picked.toArgb())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom accent") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(picked))
                    Spacer(Modifier.width(12.dp))
                    Text(hex, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(12.dp))
                Text("Hue", style = MaterialTheme.typography.bodySmall)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("Saturation", style = MaterialTheme.typography.bodySmall)
                Slider(value = sat, onValueChange = { sat = it }, valueRange = 0f..1f)
                Text("Lightness", style = MaterialTheme.typography.bodySmall)
                // clamped so the accent always has contrast against both themes
                Slider(value = light, onValueChange = { light = it }, valueRange = 0.2f..0.7f)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(hex) }) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: ShelfViewModel,
    onLoggedOut: () -> Unit,
    onOpenUpload: () -> Unit = {},
    onOpenShelves: () -> Unit = {},
    onSignIn: () -> Unit = {},
) {
    val theme by vm.store.themeFlow.collectAsState(initial = "system")
    val accent by vm.store.accentFlow.collectAsState(initial = DEFAULT_ACCENT)
    val progressStyle by vm.store.progressStyleFlow.collectAsState(initial = "circle")
    val coverSize by vm.store.coverSizeFlow.collectAsState(initial = 2)
    val skipBack by vm.store.skipBackFlow.collectAsState(initial = 10)
    val skipForward by vm.store.skipForwardFlow.collectAsState(initial = 30)
    val trackScope by vm.store.trackScopeFlow.collectAsState(initial = "book")
    val swipeAction by vm.store.swipeActionFlow.collectAsState(initial = "chapter")
    val autoNext by vm.store.autoNextFlow.collectAsState(initial = false)
    var showPicker by remember { mutableStateOf(false) }
    val state by vm.state.collectAsState()
    val localFolder by vm.store.localFolderFlow.collectAsState(initial = "")
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // system folder picker; we keep read access across restarts
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.setLocalFolder(uri.toString())
        }
    }
    val storedApiKey by vm.store.apiKeyFlow.collectAsState(initial = "")
    val shelves by vm.store.homeShelvesFlow.collectAsState(initial = emptyList())
    val customShelf by vm.store.customShelfFlow.collectAsState(initial = emptyList())
    val server by vm.store.serverFlow.collectAsState(initial = null)
    val username by vm.store.usernameFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var apiKeyText by remember(storedApiKey) { mutableStateOf(storedApiKey) }
    var showCustomShelfEditor by remember { mutableStateOf(false) }

    if (showCustomShelfEditor) {
        CustomShelfEditorDialog(
            vm = vm,
            current = customShelf,
            onDismiss = { showCustomShelfEditor = false },
            onSave = { entries ->
                scope.launch { vm.store.setCustomShelf(entries) }
                showCustomShelfEditor = false
            },
        )
    }

    if (showPicker) {
        AccentPickerDialog(
            initial = accentColorOf(accent),
            onDismiss = { showPicker = false },
            onPick = { hex -> scope.launch { vm.store.setAccent(hex) }; showPicker = false }
        )
    }

    Scaffold(
        containerColor = Fukuro.colors.background,
        // no back arrow: Settings is a bottom-nav tab, not a pushed screen
        topBar = { FlatTopBar("Settings") },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 140.dp),
        ) {
            item(key = "appearance") {
                Column {
            SectionTitle("Appearance")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = listOf(
                    "system" to "System", "light" to "Light",
                    "dark" to "Dark", "black" to "Pure black",
                ),
                selected = theme,
                onSelect = { key -> scope.launch { vm.store.setTheme(key) } },
            )
            Spacer(Modifier.height(12.dp))
            OverlineText("Accent color")
            Spacer(Modifier.height(8.dp))
            val isCustom = accent.startsWith("#")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ACCENT_COLORS.forEach { (key, pair) ->
                    val (_, color) = pair
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(color)
                            .then(
                                if (accent == key)
                                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable { scope.launch { vm.store.setAccent(key) } }
                    )
                }
                // custom colour picker, last in the row
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(
                            if (isCustom) accentColorOf(accent)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .then(
                            if (isCustom)
                                Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        .clickable { showPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Colorize, "Custom colour",
                        modifier = Modifier.size(18.dp),
                        tint = if (isCustom) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OverlineText("Cover size")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = COVER_SIZE_LABELS.mapIndexed { i, label -> i to label },
                selected = coverSize,
                onSelect = { i -> scope.launch { vm.store.setCoverSize(i) } },
            )
                }
            }

            item(key = "shelves") {
                Column {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Home")
            Spacer(Modifier.height(4.dp))
            SectionCaption(
                "Your shelves, in the order Home draws them — reorder them, switch " +
                    "one off, or add one of your own."
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FukuroChip(
                    label = shelvesSummary(shelves),
                    selected = false,
                    onClick = onOpenShelves,
                )
                FukuroChip(
                    label = if (customShelf.isEmpty()) "Hand-picked shelf"
                    else "Hand-picked (${customShelf.size})",
                    selected = false,
                    onClick = { showCustomShelfEditor = true },
                )
            }
                }
            }

            item(key = "player") {
                Column {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Player")
            Spacer(Modifier.height(8.dp))
            OverlineText("Player progress bars")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = listOf(
                    "book" to "Whole book",
                    "chapter" to "Current chapter",
                    "chapter_cover" to "Both · cover",
                    "chapter_stacked" to "Both · stacked",
                ),
                selected = trackScope,
                onSelect = { key -> scope.launch { vm.store.setTrackScope(key) } },
            )
            Spacer(Modifier.height(6.dp))
            SectionCaption(
                "Both modes use the chapter as the main seek bar and add total-book progress. " +
                    "On the cover it takes the shape set under \"How progress shows on covers\"."
            )

            Spacer(Modifier.height(12.dp))
            OverlineText("Swiping sideways on a player")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("chapter" to "Chapters", "book" to "Books in series").forEach { (key, label) ->
                    FilterChip(
                        selected = swipeAction == key,
                        onClick = { scope.launch { vm.store.setSwipeAction(key) } },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "A book that isn't part of a series always swipes between chapters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoNext, onCheckedChange = { on -> scope.launch { vm.store.setAutoNext(on) } })
                Text("Start the next book in the series when one finishes", Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            OverlineText("How progress shows on covers")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = listOf("circle" to "Circle", "bar" to "Bar on cover"),
                selected = progressStyle,
                onSelect = { key -> scope.launch { vm.store.setProgressStyle(key) } },
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Skip buttons")
            Spacer(Modifier.height(8.dp))
            OverlineText("Back")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = listOf(5, 10, 15, 30).map { it to "${it}s" },
                selected = skipBack,
                onSelect = { seconds -> scope.launch { vm.store.setSkipBack(seconds) } },
            )
            Spacer(Modifier.height(8.dp))
            OverlineText("Forward")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = listOf(15, 30, 45, 60).map { it to "${it}s" },
                selected = skipForward,
                onSelect = { seconds -> scope.launch { vm.store.setSkipForward(seconds) } },
            )
                }
            }

            item(key = "local-library") {
                Column {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Books on this phone")
            Spacer(Modifier.height(4.dp))
            SectionCaption(
                "Pick a folder and Fukuro will list the audiobooks inside it — no server needed. " +
                    "Downloads are copied there too."
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (localFolder.isBlank()) "No folder selected"
                else "${state.localCount} book(s) found\n" + prettyFolder(localFolder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }) {
                    Text(if (localFolder.isBlank()) "Choose folder" else "Change folder")
                }
                if (localFolder.isNotBlank()) {
                    OutlinedButton(onClick = { vm.rescanLocal() }, enabled = !state.scanning) {
                        Text(if (state.scanning) "Scanning…" else "Rescan")
                    }
                    OutlinedButton(onClick = { scope.launch { vm.store.setLocalFolder(""); vm.rescanLocal() } }) {
                        Text("Remove")
                    }
                }
            }
                }
            }

            item(key = "server") {
                Column {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Server")
            Spacer(Modifier.height(4.dp))
            SectionCaption(
                "API key (optional) — used for uploading new books. Create one in the " +
                    "Audiobookshelf web UI under Settings → API Keys."
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                apiKeyText, { apiKeyText = it }, singleLine = true,
                label = { Text("Audiobookshelf API key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { vm.store.setApiKey(apiKeyText.trim()) } }) { Text("Save key") }
                Button(onClick = onOpenUpload) { Text("Upload a book") }
            }
                }
            }

            item(key = "account-and-updates") {
                Column {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Account")
            Spacer(Modifier.height(4.dp))
            if (!state.loggedIn) {
                Text(
                    "Not signed in — using books on this phone only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onSignIn) { Text("Sign in to a server") }
                Spacer(Modifier.height(140.dp))
                return@Column
            }
            Text("${username ?: "?"} @ ${server ?: "?"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.logout(); onLoggedOut() }) { Text("Log out") }

            Spacer(Modifier.height(24.dp))
            Text(
                "Fukuro ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            UpdatesSection(vm)

            // if the app died last time, keep the stack trace where it can be read
            val crashFile = remember { java.io.File(context.filesDir, ShelfApp.CRASH_FILE) }
            var crashText by remember { mutableStateOf(runCatching { crashFile.readText() }.getOrNull()) }
            crashText?.let { text ->
                Spacer(Modifier.height(16.dp))
                Text("Last crash", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error)
                Text(
                    text.take(1200),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    }) { Text("Copy") }
                    OutlinedButton(onClick = { crashFile.delete(); crashText = null }) { Text("Clear") }
                }
            }
                }
            }
        }
    }
}

private val CUSTOM_SHELF_TYPES = listOf(
    "book" to "Books",
    "series" to "Series",
    "author" to "Authors",
    "narrator" to "Narrators",
)

private fun customShelfTypeLabel(type: String): String =
    CUSTOM_SHELF_TYPES.firstOrNull { it.first == type }?.second?.removeSuffix("s") ?: type

/** Edit one ordered list whose cards can point at any supported library entity. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomShelfEditorDialog(
    vm: ShelfViewModel,
    current: List<CustomShelfEntry>,
    onDismiss: () -> Unit,
    onSave: (List<CustomShelfEntry>) -> Unit,
) {
    val state by vm.state.collectAsState()
    var selected by remember(current) { mutableStateOf(current) }
    var type by remember { mutableStateOf("book") }
    var query by remember { mutableStateOf("") }

    val allCandidates = when (type) {
        "series" -> state.series.map { CustomShelfEntry("series", it.id, it.name) }
        "author" -> (state.authors.map { it.name } + state.allItems.flatMap { authorsOf(it) })
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
            .map { CustomShelfEntry("author", it, it) }
        "narrator" -> state.allItems.flatMap { narratorsOf(it) }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
            .map { CustomShelfEntry("narrator", it, it) }
        else -> state.allItems
            .sortedBy { it.media.metadata.titleIgnorePrefix ?: it.media.metadata.title }
            .map { CustomShelfEntry("book", it.id, it.media.metadata.title ?: "Untitled") }
    }
    val candidates = allCandidates
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .filterNot { candidate -> selected.any { it.type == candidate.type && it.id == candidate.id } }
        .take(80)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom shelf") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                item {
                    Text(
                        "Selected items appear on Home in this exact order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (selected.isEmpty()) {
                    item {
                        Text("Nothing selected yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                itemsIndexed(selected, key = { _, entry -> "selected:${entry.type}:${entry.id}" }) { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, maxLines = 1)
                            Text(
                                customShelfTypeLabel(entry.type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                            onClick = {
                                selected = selected.toMutableList().also {
                                    val moved = it.removeAt(index); it.add(index - 1, moved)
                                }
                            },
                        ) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                        IconButton(
                            enabled = index < selected.lastIndex,
                            modifier = Modifier.size(32.dp),
                            onClick = {
                                selected = selected.toMutableList().also {
                                    val moved = it.removeAt(index); it.add(index + 1, moved)
                                }
                            },
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                        TextButton(onClick = { selected = selected - entry }) { Text("Remove") }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Fukuro.colors.outline)
                    Spacer(Modifier.height(10.dp))
                    Text("Add items", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CUSTOM_SHELF_TYPES.forEach { (key, label) ->
                            FilterChip(
                                selected = type == key,
                                onClick = { type = key; query = "" },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Search ${CUSTOM_SHELF_TYPES.first { it.first == type }.second.lowercase()}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(candidates, key = { "candidate:${it.type}:${it.id}" }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = selected + entry }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.title, Modifier.weight(1f), maxLines = 2)
                        TextButton(onClick = { selected = selected + entry }) { Text("Add") }
                    }
                }
                if (candidates.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "No more items available" else "No matches",
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The chip that opens Customise home says how many shelves are switched on. */
private fun shelvesSummary(shelves: List<Shelf>): String {
    val on = shelves.count { it.enabled }
    if (on == 0) return "Customise home"
    return "Customise home · $on shelf" + (if (on == 1) "" else "s")
}
