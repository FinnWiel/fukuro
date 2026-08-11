package fukuro

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.scale
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay

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
            val accentPref by vm.store.accentFlow.collectAsState(initial = DEFAULT_ACCENT)
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

/** Modern nav pattern: outlined icon at rest, rounded/filled when selected. */
private data class Tab(
    val route: String,
    val label: String,
    val iconSelected: androidx.compose.ui.graphics.vector.ImageVector,
    val iconIdle: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
fun AppNav(vm: ShelfViewModel, controller: MediaController?) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: ""

    val tabs = listOf(
        Tab("home", "Home", Icons.Rounded.Home, Icons.Outlined.Home),
        Tab("library", "Library", Icons.Rounded.LibraryBooks, Icons.Outlined.LibraryBooks),
        Tab("settings", "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
    )
    // the app always opens straight into the library; the server is optional and is
    // added from the status chip on Home or from Settings
    val showChrome = route != "player" && route != "login" && !route.startsWith("book/")

    fun playBook(itemId: String) {
        val c = controller ?: return
        // hand the resume position to the service so it can start without extra network calls
        val saved = state.progress[itemId]?.takeIf { !it.isFinished && it.progress > 0.001 }?.currentTime
        val extras = Bundle().apply { putDouble("startTimeSec", saved ?: 0.0) }
        c.setMediaItem(
            MediaItem.Builder().setMediaId("${PlayerService.BOOK_PREFIX}$itemId")
                .setMediaMetadata(MediaMetadata.Builder().setExtras(extras).build()).build()
        )
        c.prepare()
        c.play()
        // already on the book page — it switches to playing mode by itself
    }

    // book sheet: null = closed, SHEET_CURRENT = whatever is playing, else an item id
    var sheetItem by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    // screens shift their bottom spacing depending on whether the mini player is up
    var miniPlayerVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = sheetItem != null) { sheetItem = null }

    // Overlay layout: content fills the screen and scrolls BEHIND the translucent chrome,
    // Spotify-style. Screens add their own bottom spacing to clear the bars.
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box {
            NavHost(nav, startDestination = "home") {
                composable("login") {
                    LoginScreen(
                        vm,
                        onBack = { if (!nav.popBackStack()) nav.navigate("home") },
                    ) { nav.navigate("home") { popUpTo("login") { inclusive = true } } }
                }
                composable("home") {
                    HomeScreen(vm,
                        onOpenServer = { nav.navigate("login") },
                        onOpenBook = { id -> sheetItem = id },
                        onOpenSeries = { id -> nav.navigate("series/$id") },
                        onOpenAuthor = { name -> nav.navigate("author/${android.net.Uri.encode(name)}") })
                }
                composable("library") {
                    LibraryScreen(
                        vm,
                        onOpenBook = { id -> sheetItem = id },
                        miniPlayerVisible = miniPlayerVisible
                    )
                }
                composable("series/{id}") { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    SeriesGridScreen(vm, id, onOpenBook = { b -> sheetItem = b }, onBack = { nav.popBackStack() })
                }
                composable("author/{name}") { entry ->
                    val name = entry.arguments?.getString("name") ?: return@composable
                    AuthorGridScreen(vm, name, onOpenBook = { b -> sheetItem = b }, onBack = { nav.popBackStack() })
                }
                // the book/now-playing page is not a destination — it is an overlay
                // sheet (see below) so the page behind stays visible while dragging
                composable("settings") {
                    SettingsScreen(
                        vm,
                        onLoggedOut = {
                            nav.navigate("login") {
                                popUpTo(nav.graph.startDestinationId) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onOpenUpload = { nav.navigate("upload") },
                        onSignIn = { nav.navigate("login") }
                    )
                }
                composable("upload") {
                    UploadScreen(vm, onBack = { nav.popBackStack() })
                }
            }
        }

        if (showChrome) Box(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                // no bar background at all — just a black gradient rising from the bottom
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to androidx.compose.ui.graphics.Color.Transparent,
                        0.45f to androidx.compose.ui.graphics.Color(0x99000000),
                        1f to androidx.compose.ui.graphics.Color(0xE8000000)
                    )
                )
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                Spacer(Modifier.height(16.dp)) // lets the gradient fade in above the mini player
                MiniPlayer(
                    vm, controller,
                    onHasMedia = { miniPlayerVisible = it },
                    onOpen = { sheetItem = SHEET_CURRENT }
                )
                NavigationBar(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0.dp),
                    modifier = Modifier.height(60.dp)
                ) {
                    tabs.forEach { tab ->
                        val selected = route == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                // bouncy scale-in on the active icon
                                val scale by animateFloatAsState(
                                    targetValue = if (selected) 1.18f else 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "navIconScale"
                                )
                                Box(Modifier.scale(scale)) {
                                    Icon(if (selected) tab.iconSelected else tab.iconIdle, tab.label)
                                }
                            },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                // white over the black gradient regardless of theme
                                selectedIconColor = androidx.compose.ui.graphics.Color.White,
                                selectedTextColor = androidx.compose.ui.graphics.Color.White,
                                unselectedIconColor = androidx.compose.ui.graphics.Color(0x8CFFFFFF),
                                unselectedTextColor = androidx.compose.ui.graphics.Color(0x8CFFFFFF),
                            )
                        )
                    }
                }
            }
        }

        // ---- the book / now-playing sheet, drawn over everything ----
        AnimatedVisibility(
            visible = sheetItem != null,
            enter = slideInVertically(tween(300)) { it },
            exit = slideOutVertically(tween(250)) { it },
        ) {
            PlayerScreen(
                vm, controller,
                itemId = sheetItem?.takeIf { it != SHEET_CURRENT },
                onBack = { sheetItem = null },
                onPlayBook = { b -> playBook(b) },
                onOpenBook = { b -> sheetItem = b },
                onOpenSeries = { s -> sheetItem = null; nav.navigate("series/$s") },
                onOpenAuthor = { name ->
                    sheetItem = null
                    nav.navigate("author/${android.net.Uri.encode(name)}")
                },
            )
        }
    }
}

/** Sentinel for "open the sheet on whatever is currently playing". */
const val SHEET_CURRENT = "__current__"

/** Spotify-style persistent mini player above the nav bar. Hidden when nothing is loaded. */
@Composable
private fun MiniPlayer(
    vm: ShelfViewModel,
    controller: MediaController?,
    onHasMedia: (Boolean) -> Unit = {},
    onOpen: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var metaTitle by androidx.compose.runtime.remember { mutableStateOf("") }
    var currentItemId by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var isPlaying by androidx.compose.runtime.remember { mutableStateOf(false) }
    var artwork by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var hasMedia by androidx.compose.runtime.remember { mutableStateOf(false) }
    var livePos by androidx.compose.runtime.remember { mutableFloatStateOf(0f) }

    // lightweight 1s poll of the shared controller
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                hasMedia = c.mediaItemCount > 0
                metaTitle = c.mediaMetadata.title?.toString() ?: ""
                currentItemId = c.currentMediaItem?.mediaId
                    ?.takeIf { it.startsWith(PlayerService.BOOK_PREFIX) }
                    ?.removePrefix(PlayerService.BOOK_PREFIX)?.substringBefore('#')
                artwork = c.mediaMetadata.artworkUri?.toString()
                isPlaying = c.isPlaying
                // live whole-book position straight from the service (c.duration is
                // only the current file, which made multi-file books read as finished)
                val f = c.sendCustomCommand(
                    SessionCommand(PlayerService.CMD_BOOK_POSITION, Bundle.EMPTY), Bundle.EMPTY
                )
                f.addListener({
                    try {
                        val b = f.get().extras
                        val pos = b.getDouble("posSec", 0.0)
                        val dur = b.getDouble("durSec", 0.0)
                        if (dur > 0) livePos = (pos / dur).toFloat().coerceIn(0f, 1f)
                    } catch (_: Exception) {}
                }, java.util.concurrent.Executor { it.run() })
            }
            delay(1000)
        }
    }

    // live value while playing; fall back to the synced position before the first tick
    val progress = if (livePos > 0f) livePos else currentItemId?.let { id ->
        state.progress[id]?.progress?.toFloat()?.coerceIn(0f, 1f)
    } ?: 0f

    // prefer live library title so a rename shows immediately
    val title = currentItemId?.let { id ->
        state.items.firstOrNull { it.id == id }?.media?.metadata?.title
    } ?: metaTitle

    LaunchedEffect(hasMedia) { onHasMedia(hasMedia) }
    if (!hasMedia) return
    // Spotify-style floating card: rounded, inset from the edges, progress hairline inside
    val author = currentItemId?.let { id ->
        state.items.firstOrNull { it.id == id }?.media?.metadata?.authorName
    } ?: ""
    // background pulled from the cover art, desaturated so text stays readable
    val ctx = LocalContext.current
    var coverColor by androidx.compose.runtime.remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artwork) {
        coverColor = null
        val url = artwork ?: return@LaunchedEffect
        coverColor = extractCoverColor(ctx, url)
    }
    val fallback = MaterialTheme.colorScheme.surfaceVariant
    val barColor by animateColorAsState(coverColor ?: fallback, tween(400), label = "miniBg")
    val onBar = if (coverColor != null) Color.White else MaterialTheme.colorScheme.onSurface
    val onBarDim = if (coverColor != null) Color(0xB3FFFFFF) else MaterialTheme.colorScheme.onSurfaceVariant

    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 2.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = barColor,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen() }
                        .padding(start = 6.dp, top = 5.dp, bottom = 5.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverImage(
                        // on-device books have no artwork uri from the session
                        model = artwork ?: currentItemId?.let { vm.coverModel(it) },
                        contentDescription = title,
                        modifier = Modifier.size(38.dp).clip(RoundedCornerShape(5.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title, style = MaterialTheme.typography.bodyMedium, color = onBar,
                            maxLines = 1, softWrap = false,
                            // scrolls itself when the title is too long, like Spotify
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        if (author.isNotBlank()) Text(
                            author, style = MaterialTheme.typography.bodySmall,
                            color = onBarDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    currentItemId?.let { id ->
                        val fav = id in state.favorites
                        IconButton(onClick = { vm.toggleFavorite(id) }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                if (fav) "Remove favorite" else "Add favorite",
                                Modifier.size(20.dp),
                                tint = if (fav) MaterialTheme.colorScheme.primary else onBarDim
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (isPlaying) controller?.pause() else controller?.play() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            "Play/Pause", Modifier.size(28.dp), tint = onBar
                        )
                    }
                    Spacer(Modifier.width(10.dp)) // breathing room to the right of play
                }
                // hand-drawn so the track is actually visible on this surface and the
                // height isn't overridden by Material's own indicator sizing
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp)
                        .height(3.dp).clip(RoundedCornerShape(2.dp))
                        .background(onBarDim.copy(alpha = 0.30f))
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

/**
 * Average colour of the whole cover: the image is scaled down and every pixel is
 * averaged, then lightly desaturated and darkened so white text stays legible.
 */
private suspend fun extractCoverColor(context: android.content.Context, url: String): Color? =
    withContext(Dispatchers.IO) {
        try {
            val req = ImageRequest.Builder(context).data(url).allowHardware(false).size(96).build()
            val drawable = ImageLoader(context).execute(req).drawable ?: return@withContext null
            val source = (drawable as? BitmapDrawable)?.bitmap ?: return@withContext null

            val w = 24
            val h = 24
            val small = android.graphics.Bitmap.createScaledBitmap(source, w, h, true)
            val pixels = IntArray(w * h)
            small.getPixels(pixels, 0, w, 0, 0, w, h)
            var r = 0L; var g = 0L; var b = 0L
            for (p in pixels) {
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            val n = pixels.size
            val avg = android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())

            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(avg, hsl)
            hsl[1] = hsl[1] * 0.85f                 // slight desaturation
            hsl[2] = hsl[2].coerceIn(0.18f, 0.40f)  // dark enough for white text
            Color(ColorUtils.HSLToColor(hsl))
        } catch (_: Exception) {
            null
        }
    }
