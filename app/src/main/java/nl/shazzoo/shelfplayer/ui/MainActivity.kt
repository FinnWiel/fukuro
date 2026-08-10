package nl.shazzoo.shelfplayer.ui

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.util.concurrent.MoreExecutors
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
            ShelfTheme(themePref) {
                AppNav(vm, controller)
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

@Composable
fun AppNav(vm: ShelfViewModel, controller: MediaController?) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()

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

    NavHost(nav, startDestination = if (state.loggedIn) "home" else "login") {
        composable("login") {
            LoginScreen(vm) { nav.navigate("home") { popUpTo("login") { inclusive = true } } }
        }
        composable("home") {
            HomeScreen(vm,
                onOpenBook = { id -> nav.navigate("book/$id") },
                onOpenPlayer = { nav.navigate("player") },
                onOpenSettings = { nav.navigate("settings") })
        }
        composable("book/{id}") { backStack ->
            val id = backStack.arguments?.getString("id") ?: return@composable
            BookScreen(vm, id, onPlay = { playBook(id) }, onBack = { nav.popBackStack() })
        }
        composable("player") {
            PlayerScreen(vm, controller, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            SettingsScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
