package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TopAppBar
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
fun SettingsScreen(vm: ShelfViewModel, onLoggedOut: () -> Unit, onOpenUpload: () -> Unit = {}) {
    val theme by vm.store.themeFlow.collectAsState(initial = "system")
    val accent by vm.store.accentFlow.collectAsState(initial = DEFAULT_ACCENT)
    val progressStyle by vm.store.progressStyleFlow.collectAsState(initial = "circle")
    val downloadDir by vm.store.downloadDirFlow.collectAsState(initial = "")
    val coverSize by vm.store.coverSizeFlow.collectAsState(initial = 2)
    val skipBack by vm.store.skipBackFlow.collectAsState(initial = 10)
    val skipForward by vm.store.skipForwardFlow.collectAsState(initial = 30)
    var showPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // app storage plus any removable volume Android gives us an app-specific dir on
    val storageOptions = remember {
        buildList {
            add("" to "App storage")
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, f ->
                add(File(f, "downloads").absolutePath to "SD card${if (i > 0) " ${i + 1}" else ""}")
            }
        }
    }
    val storedApiKey by vm.store.apiKeyFlow.collectAsState(initial = "")
    val sectionsCsv by vm.store.homeSectionsFlow.collectAsState(initial = Store.DEFAULT_SECTIONS)
    val server by vm.store.serverFlow.collectAsState(initial = null)
    val username by vm.store.usernameFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var apiKeyText by remember(storedApiKey) { mutableStateOf(storedApiKey) }

    val enabled = sectionsCsv.split(',').filter { it.isNotBlank() }
    val allKeys = Store.SECTION_LABELS.keys.toList()

    fun save(newList: List<String>) = scope.launch { vm.store.setHomeSections(newList.joinToString(",")) }

    Scaffold(topBar = {
        // no back arrow: Settings is a bottom-nav tab, not a pushed screen
        TopAppBar(title = { Text("Settings") })
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "system" to "System", "light" to "Light",
                    "dark" to "Dark", "black" to "Pure black",
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = theme == key,
                        onClick = { scope.launch { vm.store.setTheme(key) } },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Book progress", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("circle" to "Circle", "bar" to "Bar on cover").forEach { (key, label) ->
                    FilterChip(
                        selected = progressStyle == key,
                        onClick = { scope.launch { vm.store.setProgressStyle(key) } },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Cover size", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COVER_SIZE_LABELS.forEachIndexed { i, label ->
                    FilterChip(
                        selected = coverSize == i,
                        onClick = { scope.launch { vm.store.setCoverSize(i) } },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Accent color", style = MaterialTheme.typography.bodyMedium)
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

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Home screen sections", style = MaterialTheme.typography.titleMedium)
            Text("Listed in the order they appear on Home — use the arrows to reorder",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            // enabled sections first, in their real order; disabled ones after
            val ordered = enabled + allKeys.filterNot { it in enabled }

            ordered.forEach { key ->
                val label = Store.SECTION_LABELS[key] ?: key
                val isOn = key in enabled
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isOn, onCheckedChange = { on ->
                        save(if (on) enabled + key else enabled - key)
                    })
                    Text(label, Modifier.weight(1f))
                    if (isOn) {
                        val i = enabled.indexOf(key)
                        IconButton(
                            enabled = i > 0,
                            modifier = Modifier.size(32.dp),
                            onClick = {
                                val l = enabled.toMutableList(); l.removeAt(i); l.add(i - 1, key); save(l)
                            }
                        ) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                        IconButton(
                            enabled = i < enabled.size - 1,
                            modifier = Modifier.size(32.dp),
                            onClick = {
                                val l = enabled.toMutableList(); l.removeAt(i); l.add(i + 1, key); save(l)
                            }
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Skip buttons", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Back", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { s ->
                    FilterChip(
                        selected = skipBack == s,
                        onClick = { scope.launch { vm.store.setSkipBack(s) } },
                        label = { Text("${s}s") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Forward", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60).forEach { s ->
                    FilterChip(
                        selected = skipForward == s,
                        onClick = { scope.launch { vm.store.setSkipForward(s) } },
                        label = { Text("${s}s") }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            Text(
                if (storageOptions.size > 1) "Where new offline downloads are stored. Books already downloaded stay where they are."
                else "This device only exposes app storage to the app, so there is nowhere else to put downloads.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                storageOptions.forEach { (path, label) ->
                    FilterChip(
                        selected = downloadDir == path,
                        enabled = storageOptions.size > 1,
                        onClick = { scope.launch { vm.store.setDownloadDir(path) } },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Server", style = MaterialTheme.typography.titleMedium)
            Text(
                "API key (optional) — used for uploading new books. Create one in the Audiobookshelf web UI under Settings → API Keys.",
                style = MaterialTheme.typography.bodySmall
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

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Account", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("${username ?: "?"} @ ${server ?: "?"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.logout(); onLoggedOut() }) { Text("Log out") }

            if (showPicker) {
                AccentPickerDialog(
                    initial = accentColorOf(accent),
                    onDismiss = { showPicker = false },
                    onPick = { hex -> scope.launch { vm.store.setAccent(hex) }; showPicker = false }
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Fukuro ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(140.dp)) // clear the floating chrome
        }
    }
}
