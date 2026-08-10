package nl.shazzoo.shelfplayer.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import nl.shazzoo.shelfplayer.player.PlayerService

class MainActivity : ComponentActivity() {
    private val vm: ShelfViewModel by viewModels()
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())

        setContent {
            val themePref by vm.store.themeFlow.collectAsState(initial = "system")
            val accentPref by vm.store.accentFlow.collectAsState(initial = "green")
            ShelfTheme(themePref, accentPref) {
                AppNav(vm, controller)
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

private data class Tab(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun AppNav(vm: ShelfViewModel, controller: MediaController?) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ""

    val tabs = listOf(
        Tab("home", "Home") { Icon(Icons.Filled.Home, "Home") },
        Tab("library", "Library") { Icon(Icons.AutoMirrored.Filled.MenuBook, "Library") },
        Tab("settings", "Settings") { Icon(Icons.Filled.Settings, "Settings") },
    )
    val showChrome = state.loggedIn && route != "player" && route != "login"

    fun playBook(itemId: String) {
        val c = controller ?: return
        c.setMediaItem(
            MediaItem.Builder().setMediaId("${PlayerService.BOOK_PREFIX}$itemId")
                .setMediaMetadata(MediaMetadata.Builder().build()).build()
        )
        c.prepare()
        c.play()
        nav.navigate("player")
    }

    androidx.compose.material3.Scaffold(
        bottomBar = {
            if (showChrome) Column {
                MiniPlayer(controller) { nav.navigate("player") }
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = tab.icon,
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            NavHost(nav, startDestination = if (state.loggedIn) "home" else "login") {
                composable("login") {
                    LoginScreen(vm) { nav.navigate("home") { popUpTo("login") { inclusive = true } } }
                }
                composable("home") {
                    HomeScreen(vm, onOpenBook = { id -> nav.navigate("book/$id") })
                }
                composable("library") {
                    LibraryScreen(vm, onOpenBook = { id -> nav.navigate("book/$id") })
                }
                composable("book/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@composable
                    BookScreen(vm, id, onPlay = { playBook(id) }, onBack = { nav.popBackStack() })
                }
                composable("player") {
                    PlayerScreen(vm, controller, onBack = { nav.popBackStack() })
                }
                composable("settings") {
                    SettingsScreen(vm, onBack = { nav.popBackStack() }, onOpenUpload = { nav.navigate("upload") })
                }
                composable("upload") {
                    UploadScreen(vm, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

/** Spotify-style persistent mini player above the nav bar. Hidden when nothing is loaded. */
@Composable
private fun MiniPlayer(controller: MediaController?, onOpen: () -> Unit) {
    var title by androidx.compose.runtime.remember { mutableStateOf("") }
    var isPlaying by androidx.compose.runtime.remember { mutableStateOf(false) }
    var artwork by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var progress by androidx.compose.runtime.remember { mutableFloatStateOf(0f) }
    var hasMedia by androidx.compose.runtime.remember { mutableStateOf(false) }

    // lightweight 1s poll of the shared controller
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                hasMedia = c.mediaItemCount > 0
                title = c.mediaMetadata.title?.toString() ?: ""
                artwork = c.mediaMetadata.artworkUri?.toString()
                isPlaying = c.isPlaying
                val dur = c.duration
                progress = if (dur > 0) (c.currentPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f
            }
            delay(1000)
        }
    }

    if (!hasMedia) return
    Surface(tonalElevation = 3.dp) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { onOpen() }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = artwork, contentDescription = title,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { if (isPlaying) controller?.pause() else controller?.play() }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/Pause", Modifier.size(28.dp)
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp)
            )
        }
    }
}
