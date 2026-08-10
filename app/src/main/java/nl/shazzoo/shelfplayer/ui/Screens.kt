package nl.shazzoo.shelfplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import nl.shazzoo.shelfplayer.data.LibraryItem
import nl.shazzoo.shelfplayer.data.Store

/* ---------------- Login ---------------- */

@Composable
fun LoginScreen(vm: ShelfViewModel, onLoggedIn: () -> Unit) {
    val state by vm.state.collectAsState()
    var server by remember { mutableStateOf("http://192.168.2.48:13378") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Shelfplayer", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text("Connect to your Audiobookshelf server", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.login(server, user, pass) { ok -> if (ok) onLoggedIn() } },
            enabled = !state.loading, modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.loading) "Connecting…" else "Log in") }
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* ---------------- Home ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: ShelfViewModel,
    onOpenBook: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val sectionsCsv by vm.store.homeSectionsFlow.collectAsState(initial = Store.DEFAULT_SECTIONS)

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Shelfplayer")
                    Spacer(Modifier.width(10.dp))
                    // connection indicator
                    Box(
                        Modifier.size(10.dp).clip(CircleShape)
                            .background(if (state.serverOnline) Color(0xFF2FBF71) else Color(0xFFE5484D))
                    )
                }
            },
            actions = {
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
            }
        )
    }) { pad ->
        if (state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            val sections = sectionsCsv.split(',').filter { it.isNotBlank() }
            sections.forEach { section ->
                when (section) {
                    "continue" -> {
                        val inProgress = state.items.filter {
                            val p = state.progress[it.id]; p != null && !p.isFinished && p.progress > 0.001
                        }
                        if (inProgress.isNotEmpty()) {
                            item { SectionHeader("Continue Listening") }
                            item { BookRow(vm, inProgress, state, onOpenBook) }
                        }
                    }
                    "series" -> {
                        val bySeries = state.items
                            .filter { it.media.metadata.series.isNotEmpty() }
                            .groupBy { it.media.metadata.series.first().name }
                        bySeries.forEach { (name, books) ->
                            item { SectionHeader(name) }
                            item {
                                BookRow(vm, books.sortedBy {
                                    it.media.metadata.series.firstOrNull()?.sequence?.toDoubleOrNull() ?: 0.0
                                }, state, onOpenBook)
                            }
                        }
                    }
                    "downloaded" -> {
                        val downloaded = state.items.filter { it.id in state.downloadedIds }
                        if (downloaded.isNotEmpty()) {
                            item { SectionHeader("Downloaded") }
                            item { BookRow(vm, downloaded, state, onOpenBook) }
                        }
                    }
                    "authors" -> {
                        val byAuthor = state.items
                            .filter { !it.media.metadata.authorName.isNullOrBlank() }
                            .groupBy { it.media.metadata.authorName!! }
                        byAuthor.forEach { (author, books) ->
                            item { SectionHeader(author) }
                            item { BookRow(vm, books, state, onOpenBook) }
                        }
                    }
                    "all" -> {
                        item { SectionHeader("All Books") }
                        item { BookRow(vm, state.items.sortedBy { it.media.metadata.titleIgnorePrefix ?: it.media.metadata.title }, state, onOpenBook) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
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
            Column(
                Modifier.width(120.dp).padding(4.dp).clickable { onOpenBook(book.id) }
            ) {
                AsyncImage(
                    model = vm.api.coverUrl(book.id),
                    contentDescription = book.media.metadata.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                )
                val p = state.progress[book.id]
                if (p != null && p.progress > 0.001 && !p.isFinished) {
                    LinearProgressIndicator(
                        progress = { p.progress.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
                Text(
                    (book.media.metadata.title ?: "?") + (if (state.progress[book.id]?.isFinished == true) " ✓" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/* ---------------- Book detail ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(vm: ShelfViewModel, itemId: String, onPlay: () -> Unit, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val book = state.items.firstOrNull { it.id == itemId } ?: return
    val meta = book.media.metadata
    val progress = state.progress[itemId]

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(meta.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            item {
                AsyncImage(
                    model = vm.api.coverUrl(book.id), contentDescription = meta.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                )
                Spacer(Modifier.height(12.dp))
                Text(meta.title ?: "", style = MaterialTheme.typography.headlineSmall)
                meta.authorName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                meta.series.firstOrNull()?.let { s ->
                    Text("${s.name} #${s.sequence ?: "?"}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                progress?.let { p ->
                    if (p.isFinished) Text("Finished ✓", color = MaterialTheme.colorScheme.primary)
                    else if (p.progress > 0.001) {
                        LinearProgressIndicator(progress = { p.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                        Text("${(p.progress * 100).toInt()}% — ${fmtTime(p.currentTime)} in", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text(if (progress != null && progress.progress > 0.001 && !progress.isFinished) "Resume" else "Play")
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.markFinished(itemId, !(progress?.isFinished ?: false)) },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (progress?.isFinished == true) "Mark unfinished" else "Mark finished") }
                    OutlinedButton(
                        onClick = { vm.resetProgress(itemId) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset progress") }
                }
                Spacer(Modifier.height(8.dp))
                DownloadButton(vm, itemId)
                Spacer(Modifier.height(16.dp))
                meta.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun DownloadButton(vm: ShelfViewModel, itemId: String) {
    val state by vm.state.collectAsState()
    val dlStates by vm.downloadStates.collectAsState()
    val dl = dlStates[itemId]
    val isDownloaded = itemId in state.downloadedIds

    when {
        dl != null && dl.error == null -> Column {
            LinearProgressIndicator(progress = { dl.progress }, modifier = Modifier.fillMaxWidth())
            Text(
                "Downloading… ${(dl.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        dl?.error != null -> Column {
            Text(dl.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.downloads.clearError(itemId); vm.download(itemId) },
                modifier = Modifier.fillMaxWidth()) { Text("Retry download") }
        }
        isDownloaded -> OutlinedButton(onClick = { vm.deleteDownload(itemId) }, modifier = Modifier.fillMaxWidth()) {
            Text("Downloaded ✓ — tap to remove (${vm.downloads.sizeOnDisk(itemId) / 1_000_000} MB)")
        }
        else -> OutlinedButton(onClick = { vm.download(itemId) }, modifier = Modifier.fillMaxWidth()) {
            Text("Download for offline")
        }
    }
}

fun fmtTime(sec: Double): String {
    val s = sec.toLong()
    val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
