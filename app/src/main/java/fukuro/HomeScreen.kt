package fukuro

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalTime

/* ---------------------------------------------------------------------------
 * Home
 *
 * A time-aware greeting, a server pill, filter chips, the "reading now" hero and
 * then the user's own shelves in their own order. Everything below the chips is
 * built from the shelf configuration — see Shelves.kt for the model and how a
 * shelf resolves to items.
 * ------------------------------------------------------------------------- */

private fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

/** One shelf and what it currently holds. */
private data class ResolvedShelf(val shelf: Shelf, val items: ShelfItems)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: ShelfViewModel,
    onOpenBook: (String) -> Unit,
    onOpenServer: () -> Unit = {},
    onOpenNarrator: (String) -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
    onPlayBook: (String) -> Unit = {},
    playingBookId: String? = null,
    isPlaying: Boolean = false,
    miniPlayerVisible: Boolean = false,
) {
    val state by vm.state.collectAsState()
    val shelves by vm.store.homeShelvesFlow.collectAsState(initial = emptyList())
    val customShelf by vm.store.customShelfFlow.collectAsState(initial = emptyList())
    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    val c = Fukuro.colors
    val d = Fukuro.dims

    // Resolving shelves means grouping and sorting the whole library. Keep that off
    // the main thread so returning to Home can draw its first frame immediately.
    var resolved by remember { mutableStateOf<List<ResolvedShelf>>(emptyList()) }
    LaunchedEffect(
        state.allItems, state.series, state.authors,
        state.serverProgress, state.localProgress,
        state.downloadedIds, state.favorites, state.continueHidden,
        state.serverChecked, state.serverOnline,
        shelves, customShelf, filter,
    ) {
        resolved = withContext(Dispatchers.Default) {
            shelves.filter { it.enabled }
                .map { ResolvedShelf(it, resolveShelf(it, state, customShelf, filter)) }
                .filter { it.items != ShelfItems.Empty }
        }
    }

    Scaffold(containerColor = c.background) { pad ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 10.dp,
                    bottom = d.chromeHeight(miniPlayerVisible),
                ),
            ) {
                item(key = "header") { HomeHeader(state, onOpenServer) }
                item(key = "filters") { FilterChipRow(filter) { filter = it } }
                item(key = "update") { UpdateBanner(vm) }

                resolved.forEach { (shelf, items) ->
                    // the hero carries its own label as an overline, so it gets no title
                    if (effectiveLayout(shelf, items) != ShelfLayout.HERO) {
                        item(key = "title-${shelf.id}") { ShelfTitle(shelf.title) }
                    }
                    shelfBody(
                        shelf = shelf,
                        content = items,
                        vm = vm,
                        state = state,
                        onOpenBook = onOpenBook,
                        onOpenSeries = onOpenSeries,
                        onOpenAuthor = onOpenAuthor,
                        onPlayBook = onPlayBook,
                        playingBookId = playingBookId,
                        isPlaying = isPlaying,
                        onOpenNarrator = onOpenNarrator,
                    )
                }

                if (resolved.isEmpty()) item(key = "empty") { HomeEmptyState(state) }
            }
        }
    }
}

/** Greeting on the left, server status on the right. */
@Composable
private fun HomeHeader(state: UiState, onOpenServer: () -> Unit) {
    val c = Fukuro.colors
    val greeting = remember { greetingFor(LocalTime.now().hour) }
    Row(
        Modifier.fillMaxWidth().height(Fukuro.dims.headerHeight)
            .padding(horizontal = Fukuro.dims.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            greeting,
            style = Fukuro.type.greeting,
            color = c.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StatusPill(
            label = when {
                state.serverOnline -> "Server"
                state.loggedIn -> "Offline"
                else -> "Add server"
            },
            dotColor = when {
                state.serverOnline -> c.onlineDot
                state.loggedIn -> c.offlineDot
                else -> c.tertiaryText
            },
            onClick = onOpenServer,
        )
    }
}

@Composable
private fun FilterChipRow(selected: HomeFilter, onSelect: (HomeFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(top = Fukuro.dims.shelfTitleTop)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Fukuro.dims.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.chipGap),
    ) {
        HomeFilter.entries.forEach { entry ->
            FukuroChip(entry.label, entry == selected, { onSelect(entry) })
        }
    }
}

/** Nothing to show: says why, rather than leaving a blank page. */
@Composable
private fun HomeEmptyState(state: UiState) {
    val c = Fukuro.colors
    Column(
        Modifier.fillMaxWidth().padding(Fukuro.dims.screenPadding).padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                !state.loggedIn && state.localCount == 0 -> "No books yet"
                state.offline -> "Nothing on this phone matches"
                else -> "Nothing on your shelves"
            },
            style = Fukuro.type.shelfTitle,
            color = c.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !state.loggedIn && state.localCount == 0 ->
                    "Add a server, or point Fukuro at a folder of audiobooks in Settings."
                state.offline -> "Downloads and on-device books stay available while the server is away."
                else -> "Try another filter, or add a shelf under Settings → Customise home."
            },
            style = Fukuro.type.body,
            color = c.onSurfaceVariant,
        )
    }
}

/* ---------------------------------------------------------------------------
 * Shelf bodies
 * ------------------------------------------------------------------------- */

/**
 * Adds one shelf's items to the surrounding list. Row layouts add one lazy item
 * per row, so a shelf of 500 books never builds 500 cells.
 */
private fun LazyListScope.shelfBody(
    shelf: Shelf,
    content: ShelfItems,
    vm: ShelfViewModel,
    state: UiState,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenNarrator: (String) -> Unit,
    onPlayBook: (String) -> Unit,
    playingBookId: String?,
    isPlaying: Boolean,
) {
    val layout = effectiveLayout(shelf, content)
    when (content) {
        is ShelfItems.Books -> {
            if (layout == ShelfLayout.HERO) {
                val book = content.books.first()
                item(key = "hero-${shelf.id}") {
                    val p = state.progress[book.id]
                    HeroCard(
                        overline = shelf.title,
                        title = book.media.metadata.title ?: book.relPath,
                        subtitle = listOfNotNull(
                            chapterLabel(book, p?.currentTime ?: 0.0),
                            formatTimeLeft(timeLeftSeconds(book, p)),
                        ).joinToString("  ·  "),
                        progress = p?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                        cover = vm.coverModel(book.id),
                        onOpen = { onOpenBook(book.id) },
                        onPlay = { onPlayBook(book.id) },
                        isPlaying = playingBookId == book.id && isPlaying,
                        modifier = Modifier.padding(
                            start = Fukuro.dims.screenPadding,
                            end = Fukuro.dims.screenPadding,
                            top = Fukuro.dims.shelfTitleTop,
                        ),
                    )
                }
            } else if (layout == ShelfLayout.CAROUSEL) {
                item(key = "row-${shelf.id}") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Fukuro.dims.screenPadding),
                        horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.carouselGap),
                    ) {
                        items(content.books, key = { it.id }) { book ->
                            HomeBookCell(vm, book, state, onOpenBook)
                        }
                    }
                }
            } else {
                itemsIndexed(content.books, key = { _, book -> "${shelf.id}-${book.id}" }) { index, book ->
                    HomeBookRow(
                        vm, book, state, onOpenBook,
                        last = index == content.books.lastIndex,
                    )
                }
            }
        }

        is ShelfItems.SeriesGroups -> {
            itemsIndexed(content.series, key = { _, s -> "${shelf.id}-${s.id}" }) { index, series ->
                val data = seriesRowData(series, state.progress)
                SeriesProgressRow(
                    title = series.name,
                    readCount = data.readCount,
                    totalCount = data.total,
                    nextUp = data.nextUp,
                    cover = data.nextBookId?.let { vm.coverModel(it) },
                    segments = data.segments,
                    onClick = { onOpenSeries(series.id) },
                    modifier = Modifier
                        .padding(horizontal = Fukuro.dims.screenPadding)
                        .padding(bottom = if (index == content.series.lastIndex) 0.dp else Fukuro.dims.rowGap),
                )
            }
        }

        is ShelfItems.AuthorCards -> item(key = "row-${shelf.id}") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Fukuro.dims.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.carouselGap),
            ) {
                items(content.authors, key = { it.first.id.ifBlank { it.first.name } }) { (author, count) ->
                    CollectionCell(
                        covers = listOf(author.imagePath?.let { vm.api.authorImageUrl(author.id) }),
                        title = author.name,
                        subtitle = bookCount(if (count > 0) count else author.numBooks),
                        coverSize = state.coverSize,
                        onClick = { onOpenAuthor(author.name) },
                        placeholder = { OwlPlaceholder() },
                    )
                }
            }
        }

        is ShelfItems.NarratorCards -> item(key = "row-${shelf.id}") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Fukuro.dims.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.carouselGap),
            ) {
                items(content.narrators, key = { it.first }) { (name, count) ->
                    CollectionCell(
                        // the server has no narrator portraits, so the owl stands in
                        covers = listOf(null),
                        title = name,
                        subtitle = bookCount(count),
                        coverSize = state.coverSize,
                        onClick = { onOpenNarrator(name) },
                        placeholder = { OwlPlaceholder() },
                    )
                }
            }
        }

        is ShelfItems.CustomEntries -> item(key = "row-${shelf.id}") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Fukuro.dims.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.carouselGap),
            ) {
                items(content.entries, key = { "${it.type}:${it.id}" }) { entry ->
                    CustomShelfCell(vm, entry, state, onOpenBook, onOpenSeries, onOpenAuthor, onOpenNarrator)
                }
            }
        }

        ShelfItems.Empty -> Unit
    }
}

private fun bookCount(n: Int) = "$n book" + if (n == 1) "" else "s"

/** A carousel cell for one book, owning its own long-press sheet. */
@Composable
private fun HomeBookCell(
    vm: ShelfViewModel,
    book: LibraryItem,
    state: UiState,
    onOpenBook: (String) -> Unit,
) {
    val p = state.progress[book.id]
    var showOptions by remember { mutableStateOf(false) }
    if (showOptions) BookOptionsSheet(vm, book.id, onDismiss = { showOptions = false })
    CarouselCell(
        title = book.media.metadata.title ?: book.relPath,
        meta = when {
            p?.isFinished == true -> "Finished"
            p != null && p.progress > 0.001 -> formatTimeLeft(timeLeftSeconds(book, p))
            book.media.duration > 0 -> formatSpan(book.media.duration)
            else -> book.media.metadata.authorName
        },
        cover = vm.coverModel(book.id),
        progress = p?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
        finished = p?.isFinished == true,
        coverSize = state.coverSize,
        onClick = { onOpenBook(book.id) },
        onLongClick = { showOptions = true },
    )
}

/** The same book as a full-width row, for shelves laid out as rows. */
@Composable
private fun HomeBookRow(
    vm: ShelfViewModel,
    book: LibraryItem,
    state: UiState,
    onOpenBook: (String) -> Unit,
    last: Boolean,
) {
    val p = state.progress[book.id]
    var showOptions by remember { mutableStateOf(false) }
    if (showOptions) BookOptionsSheet(vm, book.id, onDismiss = { showOptions = false })
    BookProgressRow(
        title = book.media.metadata.title ?: book.relPath,
        meta = listOfNotNull(
            book.media.metadata.authorName?.takeIf { it.isNotBlank() },
            when {
                p?.isFinished == true -> "Finished"
                p != null && p.progress > 0.001 -> formatTimeLeft(timeLeftSeconds(book, p))
                book.media.duration > 0 -> formatSpan(book.media.duration)
                else -> null
            },
        ).joinToString("  ·  "),
        cover = vm.coverModel(book.id),
        progress = if (p?.isFinished == true) 1f else p?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
        onClick = { onOpenBook(book.id) },
        onLongClick = { showOptions = true },
        modifier = Modifier
            .padding(horizontal = Fukuro.dims.screenPadding)
            .padding(bottom = if (last) 0.dp else Fukuro.dims.rowGap),
    )
}

/** One entry of the hand-picked shelf, which deliberately mixes unlike things. */
@Composable
private fun CustomShelfCell(
    vm: ShelfViewModel,
    entry: CustomShelfEntry,
    state: UiState,
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenNarrator: (String) -> Unit,
) {
    when (entry.type) {
        "book" -> state.items.firstOrNull { it.id == entry.id }?.let {
            HomeBookCell(vm, it, state, onOpenBook)
        }
        "series" -> state.series.firstOrNull { it.id == entry.id }?.let { series ->
            val books = if (state.offline) series.books.filter { state.isOnDevice(it.id) } else series.books
            if (books.isNotEmpty()) CollectionCell(
                covers = books.take(4).map { vm.coverModel(it.id) },
                title = entry.title,
                subtitle = bookCount(books.size),
                coverSize = state.coverSize,
                onClick = { onOpenSeries(entry.id) },
            )
        }
        "author" -> {
            val books = state.items.filter { it.hasAuthor(entry.id) }
            if (books.isNotEmpty()) {
                val author = state.authors.firstOrNull { it.name.equals(entry.id, ignoreCase = true) }
                CollectionCell(
                    covers = listOf(author?.imagePath?.let { vm.api.authorImageUrl(author.id) }),
                    title = entry.title,
                    subtitle = bookCount(books.size),
                    coverSize = state.coverSize,
                    onClick = { onOpenAuthor(entry.id) },
                    placeholder = { OwlPlaceholder() },
                )
            }
        }
        "narrator" -> {
            val count = state.items.count { it.hasNarrator(entry.id) }
            if (count > 0) CollectionCell(
                covers = listOf(null),
                title = entry.title,
                subtitle = bookCount(count),
                coverSize = state.coverSize,
                onClick = { onOpenNarrator(entry.id) },
                placeholder = { OwlPlaceholder() },
            )
        }
    }
}

/**
 * A series, author or narrator in a carousel: a square of covers and the same
 * two-line caption a book cell uses, so the row reads as one shelf.
 */
@Composable
private fun CollectionCell(
    covers: List<Any?>,
    title: String,
    subtitle: String,
    coverSize: Int,
    onClick: () -> Unit,
    placeholder: @Composable () -> Unit = { CoverPlaceholder() },
) {
    val c = Fukuro.colors
    Column(
        Modifier.width(carouselCellWidth(coverSize))
            .combinedClickable(onClick = onClick, onLongClick = null),
    ) {
        val shape = Modifier.fillMaxWidth().aspectRatio(1f)
            .clip(RoundedCornerShape(Fukuro.dims.coverRadius))
        if (covers.size > 1) CoverMosaic(covers, title, shape)
        else CoverImage(covers.firstOrNull(), title, shape, placeholder)
        Text(
            title,
            style = Fukuro.type.captionTitle,
            color = c.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            subtitle,
            style = Fukuro.type.captionMeta,
            color = c.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Quiet strip at the top of Home when a newer release is out — otherwise the automatic
 * check would only ever be visible to someone who went looking in Settings.
 */
@Composable
private fun UpdateBanner(vm: ShelfViewModel) {
    val u by vm.update.collectAsState()
    val info = u.info
    if (info == null || u.dismissed) return
    val c = Fukuro.colors

    FlatSurface(
        Modifier.fillMaxWidth()
            .padding(horizontal = Fukuro.dims.screenPadding, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    u.downloading -> "Downloading ${info.version}… ${(u.progress * 100).toInt()}%"
                    u.needsPermission -> "Allow Fukuro to install apps"
                    else -> "Fukuro ${info.version} is available"
                },
                Modifier.weight(1f),
                style = Fukuro.type.body,
                color = c.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (u.downloading) {
                CircularProgressIndicator(
                    progress = { u.progress },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = c.accent,
                )
                Spacer(Modifier.width(12.dp))
            } else {
                TextButton(onClick = {
                    when {
                        u.needsPermission -> vm.grantInstallPermission()
                        u.file != null -> vm.installUpdate()
                        else -> vm.downloadUpdate()
                    }
                }, shape = FukuroButtonShape) {
                    Text(
                        when {
                            u.needsPermission -> "Allow"
                            u.file != null -> "Install"
                            else -> "Update"
                        },
                        color = c.accent,
                    )
                }
            }
            IconButton(onClick = { vm.dismissUpdate() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp), tint = c.onSurfaceVariant)
            }
        }
    }
}
