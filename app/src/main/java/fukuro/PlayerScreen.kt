package fukuro

import android.os.Bundle
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

// this page always sits on artwork: explicit light-on-dark colors
private val TxtPrimary = Color.White
private val TxtSecondary = Color(0xB3FFFFFF)
private val PanelBg = Color(0x59000000)

// what the blurred artwork sits on. Books with no cover used to leave the page
// see-through onto the screen behind it; this keeps it solid either way.
private val PlayerBg = Color(0xFF101312)

/**
 * The book page. Doubles as the now-playing screen:
 *  - [itemId] null  -> shows whatever is currently loaded in the player
 *  - [itemId] set   -> shows that book; if it isn't the one playing, the transport
 *                      collapses to a single Play button that starts it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    vm: ShelfViewModel,
    controller: MediaController?,
    itemId: String? = null,
    onBack: () -> Unit,
    onPlayBook: (String, Double?) -> Unit = { _, _ -> },
    onOpenBook: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    val skipBack by vm.store.skipBackFlow.collectAsState(initial = 10)
    val skipFwd by vm.store.skipForwardFlow.collectAsState(initial = 30)
    val trackScope by vm.store.trackScopeFlow.collectAsState(initial = "book")
    var playingId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<LibraryItem?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    // absolute book position/duration from the service: the controller's own position is
    // scoped to whatever the progress bars show, so it can't be used for this
    var livePosSec by remember { mutableStateOf<Double?>(null) }
    var liveDurSec by remember { mutableStateOf(0.0) }
    var sleepRemaining by remember { mutableLongStateOf(0L) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1.0f) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var chaptersExpanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                playingId = c.currentMediaItem?.mediaId
                    ?.takeIf { it.startsWith(PlayerService.BOOK_PREFIX) }
                    ?.removePrefix(PlayerService.BOOK_PREFIX)?.substringBefore('#')
                isPlaying = c.isPlaying
                speed = c.playbackParameters.speed
                val p = c.sendCustomCommand(
                    SessionCommand(PlayerService.CMD_BOOK_POSITION, Bundle.EMPTY), Bundle.EMPTY
                )
                p.addListener({
                    try {
                        val b = p.get().extras
                        livePosSec = b.getDouble("posSec", 0.0)
                        liveDurSec = b.getDouble("durSec", 0.0)
                    } catch (_: Exception) {}
                }, java.util.concurrent.Executor { it.run() })
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

    val displayId = itemId ?: playingId
    val isCurrent = displayId != null && displayId == playingId

    LaunchedEffect(displayId) {
        detail = null
        livePosSec = null
        val id = displayId ?: return@LaunchedEffect
        // a downloaded book reads from disk first: the page fills in at once and still
        // works with no server. The server copy then replaces it when there is one.
        detail = withContext(Dispatchers.IO) { vm.downloads.localItem(id) }
        if (detail == null || vm.state.value.serverOnline) {
            runCatching { vm.api.item(id) }.getOrNull()?.let { detail = it }
        }
    }

    if (displayId == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing playing") }
        return
    }

    val libItem = state.items.firstOrNull { it.id == displayId }
    val meta = libItem?.media?.metadata ?: detail?.media?.metadata
    val title = meta?.title ?: ""
    val author = meta?.authorName ?: ""
    val coverUrl = vm.coverModel(displayId)
    val saved = state.progress[displayId]
    val bookDuration = detail?.media?.duration ?: libItem?.media?.duration ?: 0.0

    val absolutePosSec =
        if (isCurrent) livePosSec ?: saved?.currentTime ?: 0.0
        else saved?.currentTime ?: 0.0
    val chapters = detail?.media?.chapters ?: emptyList()

    fun seekAbsolute(sec: Double) {
        // the service owns the track layout; hand it book seconds and let it land the seek
        controller?.sendCustomCommand(
            SessionCommand(PlayerService.CMD_SEEK_ABS, Bundle.EMPTY),
            Bundle().apply { putDouble("posSec", sec.coerceAtLeast(0.0)) }
        )
        livePosSec = sec
    }

    // pull down to dismiss: only takes over once the content can't scroll up any further
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { 96.dp.toPx() }
    val flingAssistPx = with(LocalDensity.current) { 24.dp.toPx() }
    // plain float state: updated synchronously during scroll. Using an Animatable here
    // meant launching a coroutine per scroll event, which made scrolling stutter.
    var dragPx by remember { mutableFloatStateOf(0f) }
    val dismissConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // close the gap first when scrolling back up
                if (dragPx > 0f && available.y < 0f) {
                    val take = minOf(dragPx, -available.y)
                    dragPx -= take
                    return Offset(0f, -take)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.UserInput) {
                    // Follow the finger directly. The old half-speed movement made the
                    // sheet feel heavy and forced an unnecessarily long gesture.
                    dragPx += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragPx > dismissPx || (dragPx > flingAssistPx && available.y > 900f)) {
                    onBack()
                } else if (dragPx > 0f) {
                    animate(dragPx, 0f, animationSpec = tween(130)) { v, _ -> dragPx = v }
                }
                return Velocity.Zero
            }
        }
    }

    // corners round off as the sheet is pulled away from the top of the screen
    Box(
        Modifier.fillMaxSize()
            .nestedScroll(dismissConnection)
            .offset { IntOffset(0, dragPx.roundToInt()) }
            .clip(
                RoundedCornerShape(
                    topStart = (dragPx / dismissPx * 28f).coerceIn(0f, 28f).dp,
                    topEnd = (dragPx / dismissPx * 28f).coerceIn(0f, 28f).dp
                )
            )
            .background(PlayerBg)
    ) {
        AsyncImage(
            model = coverUrl, contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(28.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x8C000000),
                    0.5f to Color(0x99000000),
                    1f to Color(0xE6000000)
                )
            )
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            // chevron down: this page slides away downwards
                            Icon(Icons.Rounded.KeyboardArrowDown, "Close", Modifier.size(32.dp), tint = TxtPrimary)
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, "More", tint = TxtPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        CoverImage(
                            model = coverUrl, contentDescription = title,
                            modifier = Modifier.fillMaxWidth(0.94f).aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .align(Alignment.CenterHorizontally)
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, color = TxtPrimary)
                                if (author.isNotBlank()) {
                                    Text(author, style = MaterialTheme.typography.bodyMedium, color = TxtSecondary)
                                }
                                if (saved?.isFinished == true) {
                                    Text("Finished ✓", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            val fav = displayId in state.favorites
                            IconButton(onClick = { vm.toggleFavorite(displayId) }) {
                                Icon(
                                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    if (fav) "Remove favorite" else "Add favorite",
                                    tint = if (fav) MaterialTheme.colorScheme.primary else TxtPrimary
                                )
                            }
                            DownloadIconButton(vm, displayId)
                        }
                        if (sleepRemaining > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text("Sleep in ${sleepRemaining / 60}:${"%02d".format(sleepRemaining % 60)}",
                                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(12.dp))

                        // The scrubber spans either the whole book or just the current
                        // chapter, per the Settings choice. Positions stay absolute
                        // underneath; only the window shown on the track changes.
                        val bookSec = (if (bookDuration > 0) bookDuration else liveDurSec)
                            .coerceAtLeast(1.0)
                        val currentChapter = chapters.firstOrNull {
                            absolutePosSec >= it.start && absolutePosSec < it.end
                        }
                        val perChapter = trackScope == "chapter" && currentChapter != null
                        val spanStart = if (perChapter) currentChapter!!.start else 0.0
                        val spanLen = if (perChapter) {
                            (currentChapter!!.end - currentChapter.start).coerceAtLeast(1.0)
                        } else bookSec

                        val accent = MaterialTheme.colorScheme.primary
                        // while dragging, follow the finger; otherwise follow playback
                        var dragSec by remember { mutableStateOf<Float?>(null) }
                        val shownSec = (dragSec?.toDouble() ?: (absolutePosSec - spanStart))
                            .coerceIn(0.0, spanLen)
                        val frac = (shownSec / spanLen).toFloat().coerceIn(0f, 1f)

                        Scrubber(
                            fraction = frac,
                            enabled = isCurrent,
                            accent = accent,
                            onScrub = { f -> dragSec = f * spanLen.toFloat() },
                            onScrubEnd = { f -> seekAbsolute(spanStart + f * spanLen); dragSec = null }
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmtMs((shownSec * 1000).toLong()),
                                style = MaterialTheme.typography.bodySmall, color = TxtSecondary)
                            Text(
                                if (perChapter) "-${fmtMs(((spanLen - shownSec) * 1000).toLong())}"
                                else fmtMs((spanLen * 1000).toLong()),
                                style = MaterialTheme.typography.bodySmall, color = TxtSecondary
                            )
                        }
                        if (perChapter) {
                            Text(
                                currentChapter!!.title.ifBlank { "Chapter" } +
                                    " · ${fmtMs((absolutePosSec * 1000).toLong())} of ${fmtMs((bookSec * 1000).toLong())}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TxtSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        // sleep and speed pinned to the edges, transport centred with room
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // speed on the left, accent when it isn't 1x
                            TextButton(
                                onClick = { showSpeedDialog = true },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(
                                    "${fmtSpeed(speed)}x",
                                    maxLines = 1, softWrap = false,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (abs(speed - 1f) > 0.01f) MaterialTheme.colorScheme.primary
                                    else TxtSecondary
                                )
                            }
                            // skip buttons are always visible; dimmed until the book is loaded
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                IconButton(
                                    enabled = isCurrent,
                                    onClick = {
                                        controller?.let {
                                            it.seekTo((it.currentPosition - skipBack * 1000L).coerceAtLeast(0))
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.Replay10, "Back $skipBack seconds", Modifier.size(40.dp),
                                        tint = if (isCurrent) TxtPrimary else TxtPrimary.copy(alpha = 0.35f)
                                    )
                                }
                                PlayPauseKnockout(
                                    isPlaying = isCurrent && isPlaying,
                                    onClick = {
                                        if (!isCurrent) onPlayBook(displayId, null)
                                        else if (isPlaying) controller?.pause() else controller?.play()
                                    }
                                )
                                IconButton(
                                    enabled = isCurrent,
                                    onClick = {
                                        controller?.let { it.seekTo(it.currentPosition + skipFwd * 1000L) }
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.Forward30, "Forward $skipFwd seconds", Modifier.size(40.dp),
                                        tint = if (isCurrent) TxtPrimary else TxtPrimary.copy(alpha = 0.35f)
                                    )
                                }
                            }
                            // sleep timer on the right, accent when running
                            IconButton(onClick = { showSleepDialog = true }) {
                                Icon(
                                    Icons.Filled.Bedtime, "Sleep timer", Modifier.size(26.dp),
                                    tint = if (sleepRemaining > 0) MaterialTheme.colorScheme.primary
                                    else TxtSecondary
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // ----- chapters -----
                if (chapters.isNotEmpty()) {
                    val currentCh = chapters.firstOrNull { absolutePosSec >= it.start && absolutePosSec < it.end }
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { chaptersExpanded = !chaptersExpanded }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Chapters (${chapters.size})", style = MaterialTheme.typography.titleMedium,
                                color = TxtPrimary, modifier = Modifier.weight(1f)
                            )
                            val rot by animateFloatAsState(if (chaptersExpanded) 180f else 0f, label = "chevron")
                            Icon(
                                Icons.Filled.ExpandMore, if (chaptersExpanded) "Collapse" else "Expand",
                                tint = TxtSecondary, modifier = Modifier.rotate(rot)
                            )
                        }
                    }
                    if (!chaptersExpanded) {
                        currentCh?.let { ch ->
                            item {
                                val inChapter = absolutePosSec - ch.start
                                val chLen = (ch.end - ch.start).coerceAtLeast(1.0)
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                        .clip(RoundedCornerShape(10.dp)).background(PanelBg)
                                        .clickable { chaptersExpanded = true }
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        ch.title.ifBlank { "Chapter ${chapters.indexOf(ch) + 1}" },
                                        style = MaterialTheme.typography.bodyMedium, color = TxtPrimary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${fmtMs((inChapter * 1000).toLong())} / ${fmtMs((chLen * 1000).toLong())} in this chapter",
                                        style = MaterialTheme.typography.bodySmall, color = TxtSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        items(chapters.size) { i ->
                            val ch: Chapter = chapters[i]
                            val current = absolutePosSec >= ch.start && absolutePosSec < ch.end
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable {
                                        if (isCurrent) seekAbsolute(ch.start)
                                        else onPlayBook(displayId, ch.start)
                                    }
                                    .background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    ch.title.ifBlank { "Chapter ${i + 1}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (current) MaterialTheme.colorScheme.primary else TxtPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    // per-chapter mode cares about how long each chapter
                                    // is; whole-book mode wants where it starts
                                    if (trackScope == "chapter") fmtMs(((ch.end - ch.start) * 1000).toLong())
                                    else fmtMs((ch.start * 1000).toLong()),
                                    style = MaterialTheme.typography.bodySmall, color = TxtSecondary
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                // ----- series -----
                val seriesOfBook = state.series.firstOrNull { s -> s.books.any { b -> b.id == displayId } }
                if (seriesOfBook != null) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenSeries(seriesOfBook.id) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Series", style = MaterialTheme.typography.titleMedium, color = TxtPrimary)
                                Text(
                                    "${seriesOfBook.name} • ${seriesOfBook.books.size} books",
                                    style = MaterialTheme.typography.bodyMedium, color = TxtSecondary
                                )
                            }
                            Text("View", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp)) {
                            items(seriesOfBook.books, key = { it.id }) { b ->
                                Column(Modifier.width(110.dp).padding(4.dp).clickable { onOpenBook(b.id) }) {
                                    CoverImage(
                                        vm.coverModel(b.id), b.media.metadata.title,
                                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                                    )
                                    Text(
                                        b.media.metadata.title ?: "", style = MaterialTheme.typography.bodySmall,
                                        color = TxtPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ----- author -----
                if (author.isNotBlank()) {
                    item {
                        Row(
                            // co-authors live in one string; open the first one's page
                            Modifier.fillMaxWidth()
                                .clickable { onOpenAuthor(author.split(',', ';', '&').first().trim()) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Author", style = MaterialTheme.typography.titleMedium, color = TxtPrimary)
                                Text(author, style = MaterialTheme.typography.bodyMedium, color = TxtSecondary)
                            }
                            Text("View", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // ----- about -----
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("About", style = MaterialTheme.typography.titleMedium, color = TxtPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                bookDuration.takeIf { it > 0 }?.let { fmtSec(it) },
                                meta?.narratorName?.takeIf { it.isNotBlank() }?.let { "Narrated by $it" },
                                meta?.publishedYear?.takeIf { it.isNotBlank() }
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall, color = TxtSecondary
                        )
                        meta?.description?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = TxtSecondary)
                        }
                    }
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }

    if (menuOpen) {
        BookOptionsSheet(
            vm, displayId,
            onDismiss = { menuOpen = false },
            onResetSeek = { if (isCurrent) controller?.seekTo(0, 0) }
        )
    }
    if (showSpeedDialog) {
        var customSpeed by remember { mutableStateOf("") }
        val customValue = customSpeed.replace(',', '.').toFloatOrNull()
        ModalBottomSheet(onDismissRequest = { showSpeedDialog = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Playback speed", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${fmtSpeed(speed)}x", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f).forEach { s ->
                        FilterChip(
                            selected = abs(speed - s) < 0.01f,
                            onClick = { controller?.setPlaybackSpeed(s) },
                            label = { Text("${fmtSpeed(s)}x") }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        customSpeed, { customSpeed = it }, singleLine = true,
                        label = { Text("Custom (0.1x – 10x)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            customValue?.let { v ->
                                controller?.setPlaybackSpeed(v.coerceIn(0.1f, 10.0f)); customSpeed = ""
                            }
                        },
                        enabled = customValue != null && customValue > 0f
                    ) { Text("Set") }
                }
                if (customValue != null && customValue > 10f) {
                    Text("Capped at 10x", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showSleepDialog) {
        var customSleep by remember { mutableStateOf("") }
        val customMin = customSleep.toIntOrNull()
        fun setTimer(minutes: Int) {
            controller?.sendCustomCommand(
                SessionCommand(PlayerService.CMD_SLEEP_TIMER, Bundle.EMPTY),
                Bundle().apply { putInt("minutes", minutes) })
        }
        ModalBottomSheet(onDismissRequest = { showSleepDialog = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sleep timer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (sleepRemaining > 0) {
                        Text("${sleepRemaining / 60}:${"%02d".format(sleepRemaining % 60)} left",
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Playback fades out and pauses when the timer ends.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 30, 45, 60, 90, 120).forEach { min ->
                        FilterChip(selected = false, onClick = { setTimer(min); showSleepDialog = false },
                            label = { Text("$min min") })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        customSleep, { customSleep = it }, singleLine = true,
                        label = { Text("Custom minutes") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { customMin?.let { m -> setTimer(m.coerceIn(1, 720)); showSleepDialog = false } },
                        enabled = customMin != null && customMin > 0
                    ) { Text("Start") }
                }
                if (sleepRemaining > 0) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { setTimer(0); showSleepDialog = false },
                        modifier = Modifier.fillMaxWidth()) { Text("Cancel timer") }
                }
            }
        }
    }
}

/**
 * Progress bar + position dot, drawn from scratch. Material's Slider forces its own
 * track height and thumb padding, which left the dot sitting above the line.
 */
@Composable
private fun Scrubber(
    fraction: Float,
    enabled: Boolean,
    accent: Color,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(1) }
    var localFrac by remember { mutableStateOf<Float?>(null) }
    val shown = (localFrac ?: fraction).coerceIn(0f, 1f)
    val dot = 11.dp
    val dotPx = with(LocalDensity.current) { dot.toPx() }

    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    detectTapGestures { pos -> onScrubEnd((pos.x / size.width).coerceIn(0f, 1f)) }
                }
            )
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            val f = (pos.x / size.width).coerceIn(0f, 1f)
                            localFrac = f; onScrub(f)
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            val f = ((localFrac ?: fraction) + delta / size.width).coerceIn(0f, 1f)
                            localFrac = f; onScrub(f)
                        },
                        onDragEnd = { localFrac?.let { onScrubEnd(it) }; localFrac = null },
                        onDragCancel = { localFrac = null },
                    )
                }
            ),
        contentAlignment = Alignment.CenterStart // keeps the dot centred on the line
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(shown).height(3.dp).clip(CircleShape).background(accent))
        val x = (widthPx * shown - dotPx / 2f).roundToInt().coerceIn(0, (widthPx - dotPx).toInt().coerceAtLeast(0))
        Box(Modifier.offset { IntOffset(x, 0) }.size(dot).clip(CircleShape).background(accent))
    }
}

/** One row in a bottom sheet: icon + label. */
@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Download / downloading / downloaded-tap-to-remove, as a single icon button. */
@Composable
private fun DownloadIconButton(vm: ShelfViewModel, itemId: String) {
    val state by vm.state.collectAsState()
    val dlStates by vm.downloadStates.collectAsState()
    val dl = dlStates[itemId]
    val isDownloaded = itemId in state.downloadedIds
    var confirmRemove by remember { mutableStateOf(false) }

    if (confirmRemove) {
        val mb = remember(itemId) { vm.downloads.sizeOnDisk(itemId) / 1_000_000 }
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove download?") },
            text = { Text("Deletes the offline copy from this device and frees $mb MB. The book stays on your server.") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; vm.deleteDownload(itemId) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }

    when {
        dl != null && dl.error == null -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { dl.progress },
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.5.dp,
                trackColor = Color(0x40FFFFFF)
            )
        }
        dl?.error != null -> IconButton(onClick = { vm.downloads.clearError(itemId); vm.download(itemId) }) {
            Icon(Icons.Rounded.Download, "Retry download", tint = MaterialTheme.colorScheme.error)
        }
        isDownloaded -> IconButton(onClick = { confirmRemove = true }) {
            Icon(Icons.Rounded.DownloadDone, "Downloaded — tap to remove", tint = MaterialTheme.colorScheme.primary)
        }
        else -> IconButton(onClick = { vm.download(itemId) }) {
            Icon(Icons.Rounded.Download, "Download for offline", tint = TxtPrimary)
        }
    }
}

/**
 * Accent-filled disc with the play/pause glyph punched straight through it, so the
 * artwork behind shows in the glyph.
 */
@Composable
private fun PlayPauseKnockout(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawBehind {
                drawCircle(Color.White)
                val cx = size.width / 2f
                val cy = size.height / 2f
                val glyph = size.minDimension * 0.42f
                if (isPlaying) {
                    val barW = glyph * 0.32f
                    val gap = glyph * 0.30f
                    listOf(cx - gap / 2f - barW, cx + gap / 2f).forEach { left ->
                        drawRoundRect(
                            color = Color.Black,
                            topLeft = Offset(left, cy - glyph / 2f),
                            size = Size(barW, glyph),
                            cornerRadius = CornerRadius(barW * 0.3f),
                            blendMode = BlendMode.DstOut
                        )
                    }
                } else {
                    val w = glyph * 0.9f
                    val nudge = w * 0.12f
                    val path = Path().apply {
                        moveTo(cx - w / 2f + nudge, cy - glyph / 2f)
                        lineTo(cx + w / 2f + nudge, cy)
                        lineTo(cx - w / 2f + nudge, cy + glyph / 2f)
                        close()
                    }
                    drawPath(path, Color.Black, blendMode = BlendMode.DstOut)
                }
            }
    )
}

private fun fmtSpeed(f: Float): String = "%.2f".format(f).trimEnd('0').trimEnd('.', ',')

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun fmtSec(sec: Double): String {
    val s = sec.toLong()
    val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
