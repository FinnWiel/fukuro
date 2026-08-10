package nl.shazzoo.shelfplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.shazzoo.shelfplayer.data.Store

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ShelfViewModel, onBack: () -> Unit, onOpenUpload: () -> Unit = {}) {
    val theme by vm.store.themeFlow.collectAsState(initial = "system")
    val accent by vm.store.accentFlow.collectAsState(initial = "green")
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
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {

            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                    FilterChip(
                        selected = theme == key,
                        onClick = { scope.launch { vm.store.setTheme(key) } },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Accent color", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ACCENT_COLORS.forEach { (key, pair) ->
                    val (_, color) = pair
                    Box(
                        Modifier.size(if (accent == key) 36.dp else 28.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (accent == key) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier
                            )
                            .clickable { scope.launch { vm.store.setAccent(key) } }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = accent == "dynamic",
                onClick = { scope.launch { vm.store.setAccent("dynamic") } },
                label = { Text("Material You (wallpaper colors)") }
            )

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Home screen sections", style = MaterialTheme.typography.titleMedium)
            Text("Toggle and reorder what appears on your home screen",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            allKeys.forEach { key ->
                val label = Store.SECTION_LABELS[key] ?: key
                val isOn = key in enabled
                val idx = enabled.indexOf(key)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isOn, onCheckedChange = { on ->
                        save(if (on) enabled + key else enabled - key)
                    })
                    Text(label, Modifier.weight(1f))
                    if (isOn) {
                        IconButton(enabled = idx > 0, onClick = {
                            val l = enabled.toMutableList(); l.removeAt(idx); l.add(idx - 1, key); save(l)
                        }) { Icon(Icons.Filled.ArrowUpward, "Up") }
                        IconButton(enabled = idx < enabled.size - 1, onClick = {
                            val l = enabled.toMutableList(); l.removeAt(idx); l.add(idx + 1, key); save(l)
                        }) { Icon(Icons.Filled.ArrowDownward, "Down") }
                    }
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
            OutlinedButton(onClick = { vm.logout(); onBack() }) { Text("Log out") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
