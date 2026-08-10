package nl.shazzoo.shelfplayer.ui

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import nl.shazzoo.shelfplayer.player.PlayerService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: ShelfViewModel, controller: MediaController?, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var artwork by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var sleepRemaining by remember { mutableLongStateOf(0L) }
    var showSleepDialog by remember { mutableStateOf(false) }

    // poll player state (simple + robust for v0.1)
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                title = c.mediaMetadata.title?.toString() ?: ""
                artist = c.mediaMetadata.artist?.toString() ?: ""
                artwork = c.mediaMetadata.artworkUri?.toString()
                isPlaying = c.isPlaying
                positionMs = c.currentPosition
                durationMs = c.duration.coerceAtLeast(0)
                val f = c.sendCustomCommand(
                    SessionCommand(PlayerService.CMD_SLEEP_REMAINING, Bundle.EMPTY), Bundle.EMPTY
                )
                f.addListener({
                    try { sleepRemaining = f.get().extras.getLong("remainingSec", 0) } catch (_: Exception) {}
                }, java.util.concurrent.Executor { it.run() })
            }
            delay(1000)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Now Playing") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { showSleepDialog = true }) {
                    Icon(
                        Icons.Filled.Bedtime, "Sleep timer",
                        tint = if (sleepRemaining > 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AsyncImage(
                model = artwork, contentDescription = title,
                modifier = Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.height(24.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(artist, style = MaterialTheme.typography.bodyMedium)
            if (sleepRemaining > 0) {
                Spacer(Modifier.height(4.dp))
                Text("Sleep in ${sleepRemaining / 60}:${"%02d".format(sleepRemaining % 60)}",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            if (durationMs > 0) {
                Slider(
                    value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { controller?.seekTo(it.toLong()) },
                    valueRange = 0f..durationMs.toFloat()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtMs(positionMs), style = MaterialTheme.typography.bodySmall)
                    Text(fmtMs(durationMs), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }) {
                    Icon(Icons.Filled.SkipPrevious, "Previous file", Modifier.size(32.dp))
                }
                IconButton(onClick = { controller?.seekBack() }) {
                    Icon(Icons.Filled.Replay10, "Back 10s", Modifier.size(36.dp))
                }
                FilledIconButton(onClick = { if (isPlaying) controller?.pause() else controller?.play() },
                    modifier = Modifier.size(72.dp)) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause", Modifier.size(40.dp))
                }
                IconButton(onClick = { controller?.seekForward() }) {
                    Icon(Icons.Filled.Forward30, "Forward 30s", Modifier.size(36.dp))
                }
                IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                    Icon(Icons.Filled.SkipNext, "Next file", Modifier.size(32.dp))
                }
            }
        }
    }

    if (showSleepDialog) {
        AlertDialog(
            onDismissRequest = { showSleepDialog = false },
            title = { Text("Sleep timer") },
            text = {
                Column {
                    listOf(10, 20, 30, 45, 60, 90).forEach { min ->
                        TextButton(onClick = {
                            controller?.sendCustomCommand(
                                SessionCommand(PlayerService.CMD_SLEEP_TIMER, Bundle.EMPTY),
                                Bundle().apply { putInt("minutes", min) })
                            showSleepDialog = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("$min minutes") }
                    }
                    if (sleepRemaining > 0) {
                        TextButton(onClick = {
                            controller?.sendCustomCommand(
                                SessionCommand(PlayerService.CMD_SLEEP_TIMER, Bundle.EMPTY),
                                Bundle().apply { putInt("minutes", 0) })
                            showSleepDialog = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("Cancel timer") }
                    }
                }
            },
            confirmButton = {}
        )
    }
}

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
