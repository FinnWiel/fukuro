package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import kotlin.math.roundToInt
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
                        SettingsButton(onClick = { vm.grantInstallPermission() }) { Text("Allow") }
                        SettingsButton(onClick = { vm.installUpdate() }) { Text("Install") }
                    }
                }
                u.file != null -> SettingsButton(onClick = { vm.installUpdate() }) { Text("Install") }
                else -> SettingsButton(onClick = { vm.downloadUpdate() }) { Text("Download and install") }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsButton(
            onClick = { vm.checkForUpdate(manual = true) },
            enabled = !u.checking && !u.downloading,
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
        confirmButton = { TextButton(onClick = { onPick(hex) }, shape = FukuroButtonShape) { Text("Use colour") } },
        dismissButton = { TextButton(onClick = onDismiss, shape = FukuroButtonShape) { Text("Cancel") } }
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
    val autoRemoveCompletedDownloads by vm.store.autoRemoveCompletedDownloadsFlow.collectAsState(initial = false)
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
    val server by vm.store.serverFlow.collectAsState(initial = null)
    val username by vm.store.usernameFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var apiKeyText by remember(storedApiKey) { mutableStateOf(storedApiKey) }
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
                modifier = Modifier.fillMaxWidth(),
                fillWidth = true,
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

                }
            }

            item(key = "shelves") {
                Column {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Home")
            Spacer(Modifier.height(8.dp))
            OverlineText("Cover size")
            Spacer(Modifier.height(8.dp))
            SegmentedSelector(
                options = COVER_SIZE_LABELS.mapIndexed { index, label -> index to label },
                selected = coverSize,
                onSelect = { value -> scope.launch { vm.store.setCoverSize(value) } },
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(12.dp))
            SettingsButton(
                onClick = onOpenShelves,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Customise shelves") }
                }
            }

            item(key = "player") {
                Column {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Fukuro.colors.outline)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Player")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoNext, onCheckedChange = { on -> scope.launch { vm.store.setAutoNext(on) } })
                Text("Start the next book when one finishes", Modifier.weight(1f))
                SettingInfo("Automatically starts the next book when the current book belongs to a series.")
            }
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = autoRemoveCompletedDownloads,
                    onCheckedChange = { on -> scope.launch { vm.store.setAutoRemoveCompletedDownloads(on) } },
                )
                Text("Remove downloads after finishing", Modifier.weight(1f))
                SettingInfo("Deletes a downloaded book from this phone after it is marked finished or reaches the end. Server and progress data are kept.")
            }

            Spacer(Modifier.height(12.dp))
            SettingLabel("Progress on covers", "Choose whether book progress is drawn as a circle or as a bar across the cover.")
            Spacer(Modifier.height(8.dp))
            SegmentedSelector(
                options = listOf("circle" to "Circle", "bar" to "Bar"),
                selected = progressStyle,
                onSelect = { key -> scope.launch { vm.store.setProgressStyle(key) } },
            )

            Spacer(Modifier.height(12.dp))
            SettingLabel("Player progress bars", "Both modes use chapter progress for seeking and add whole-book progress on the cover or as a second bar.")
            Spacer(Modifier.height(8.dp))
            ProgressBarStylePicker(
                selected = trackScope,
                onSelect = { key -> scope.launch { vm.store.setTrackScope(key) } },
            )

            Spacer(Modifier.height(12.dp))
            SettingLabel("Player swipe", "Books without a series always swipe between chapters.")
            Spacer(Modifier.height(8.dp))
            SegmentedSelector(
                options = listOf("chapter" to "Chapters", "book" to "Books in series"),
                selected = swipeAction,
                onSelect = { key -> scope.launch { vm.store.setSwipeAction(key) } },
            )

            Spacer(Modifier.height(16.dp))
            SectionTitle("Skip buttons")
            Spacer(Modifier.height(8.dp))
            SkipSlider("Back", skipBack) { seconds -> scope.launch { vm.store.setSkipBack(seconds) } }
            Spacer(Modifier.height(8.dp))
            SkipSlider("Forward", skipForward) { seconds -> scope.launch { vm.store.setSkipForward(seconds) } }
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
                SettingsButton(onClick = { folderPicker.launch(null) }) {
                    Text(if (localFolder.isBlank()) "Choose folder" else "Change folder")
                }
                if (localFolder.isNotBlank()) {
                    SettingsButton(onClick = { vm.rescanLocal() }, enabled = !state.scanning) {
                        Text(if (state.scanning) "Scanning…" else "Rescan")
                    }
                    SettingsButton(onClick = { scope.launch { vm.store.setLocalFolder(""); vm.rescanLocal() } }) {
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
                SettingsButton(onClick = { scope.launch { vm.store.setApiKey(apiKeyText.trim()) } }) { Text("Save key") }
                SettingsButton(onClick = onOpenUpload) { Text("Upload a book") }
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
                SettingsButton(onClick = onSignIn) { Text("Sign in to a server") }
                Spacer(Modifier.height(140.dp))
                return@Column
            }
            Text("${username ?: "?"} @ ${server ?: "?"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            SettingsButton(onClick = { vm.logout(); onLoggedOut() }) { Text("Log out") }

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
                    SettingsButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    }) { Text("Copy") }
                    SettingsButton(onClick = { crashFile.delete(); crashText = null }) { Text("Clear") }
                }
            }
                }
            }
        }
    }
}

@Composable
private fun SettingLabel(label: String, info: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OverlineText(label, Modifier.weight(1f))
        SettingInfo(info)
    }
}

@Composable
private fun SettingInfo(text: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Rounded.Info, contentDescription = "More information", modifier = Modifier.size(18.dp), tint = Fukuro.colors.onSurfaceVariant)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { open = false }, shape = FukuroButtonShape) { Text("Got it") } },
    )
}

@Composable
private fun SettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = OutlinedButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    shape = FukuroButtonShape,
    colors = ButtonDefaults.outlinedButtonColors(contentColor = Fukuro.colors.onBackground),
    content = content,
)

@Composable
private fun <T> SegmentedSelector(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(42.dp).clip(shape)
            .border(1.dp, Fukuro.colors.outline, shape)
    ) {
        val selectedIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        val segmentWidth = maxWidth / options.size
        val highlightX by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = tween(durationMillis = 220),
            label = "selector",
        )
        Box(
            Modifier.offset(x = highlightX).width(segmentWidth).fillMaxHeight()
                .background(Fukuro.colors.accent)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = if (selected == value) Fukuro.type.chipSelected else Fukuro.type.chip,
                        color = if (selected == value) Fukuro.colors.onAccent else Fukuro.colors.onBackground)
                }
            }
        }
    }
}

@Composable
private fun SkipSlider(label: String, seconds: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OverlineText(label, Modifier.weight(1f))
        Text("${seconds}s", style = MaterialTheme.typography.labelLarge, color = Fukuro.colors.onBackground)
    }
    Slider(
        value = seconds.toFloat(),
        onValueChange = { onChange((it / 5f).roundToInt() * 5) },
        valueRange = 0f..60f,
        steps = 11,
        modifier = Modifier.fillMaxWidth().height(32.dp),
    )
}

@Composable
private fun ProgressBarStylePicker(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        Triple("book", "Whole book", "One overall timeline"),
        Triple("chapter", "Chapter", "Seek within the chapter"),
        Triple("chapter_cover", "Cover + chapter", "Book progress on artwork"),
        Triple("chapter_stacked", "Two timelines", "Chapter above whole book"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { rowOptions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { (key, title, subtitle) ->
                    ProgressBarStyleCard(
                        key = key,
                        title = title,
                        subtitle = subtitle,
                        selected = selected == key,
                        onClick = { onSelect(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressBarStyleCard(
    key: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor by androidx.compose.animation.animateColorAsState(
        if (selected) Fukuro.colors.accent else Fukuro.colors.outline,
        label = "progressStyleBorder",
    )
    Column(
        modifier.clip(shape).background(Fukuro.colors.surface)
            .border(if (selected) 2.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = Fukuro.colors.onBackground,
                maxLines = 1,
            )
            if (selected) Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = Fukuro.colors.accent,
                modifier = Modifier.size(17.dp),
            )
        }
        ProgressBarPreview(key)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = Fukuro.colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProgressBarPreview(style: String) {
    val trackShape = RoundedCornerShape(2.dp)
    val track: @Composable (Float, Color) -> Unit = { fraction, color ->
        Box(Modifier.fillMaxWidth().height(4.dp).clip(trackShape).background(Fukuro.colors.outline)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(color))
        }
    }
    Column(
        Modifier.fillMaxWidth().height(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (style) {
            "book" -> track(0.28f, Fukuro.colors.accent)
            "chapter" -> track(0.42f, Fukuro.colors.accent)
            "chapter_cover" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { track(0.42f, Fukuro.colors.accent) }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                        .border(3.dp, Fukuro.colors.accent, RoundedCornerShape(3.dp)),
                )
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                track(0.42f, Fukuro.colors.accent)
                track(0.68f, Fukuro.colors.onSurfaceVariant)
            }
        }
    }
}
