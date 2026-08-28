package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One series, in the same flat language as Home: a "reading now" hero for the book
 * you are on, the series' own segmented progress, and every book as a row. No
 * backdrop, no glass — the covers carry the colour.
 */
@Composable
fun SeriesOverviewScreen(
    series: AbsSeries,
    books: List<LibraryItem>,
    progress: Map<String, MediaProgress>,
    playingBookId: String?,
    coverModel: (String) -> Any?,
    allFavorite: Boolean,
    busy: Boolean,
    downloadedCount: Int,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onPin: () -> Unit,
    onDownloadAll: () -> Unit,
    onRemoveDownloads: () -> Unit,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    // Covers are resolved per book and the list redraws often; look each one up once.
    val coverCache = remember(books, downloadedCount) { HashMap<String, Any?>() }
    val cachedCover: (String) -> Any? = { id ->
        if (!coverCache.containsKey(id)) coverCache[id] = coverModel(id)
        coverCache[id]
    }

    val currentBook = books.firstOrNull { it.id == playingBookId }
        ?: books.firstOrNull { book ->
            progress[book.id]?.let { !it.isFinished && it.progress > 0.001 } == true
        }
        ?: books.firstOrNull { progress[it.id]?.isFinished != true }
        ?: books.lastOrNull()
    val currentIndex = books.indexOfFirst { it.id == currentBook?.id }
    val row = seriesRowData(series.copy(books = books), progress)
    val totalDuration = books.sumOf { it.media.duration.coerceAtLeast(0.0) }
    val listened = books.sumOf { book ->
        val p = progress[book.id]
        when {
            p?.isFinished == true -> book.media.duration
            p != null -> p.currentTime.coerceIn(0.0, book.media.duration.coerceAtLeast(0.0))
            else -> 0.0
        }
    }
    val author = books.firstNotNullOfOrNull { it.media.metadata.authorName?.takeIf(String::isNotBlank) }

    Scaffold(
        containerColor = c.background,
        topBar = {
            FlatTopBar(series.name, onBack) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (allFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (allFavorite) "Remove series from favorites" else "Add series to favorites",
                        tint = if (allFavorite) c.accent else c.onSurfaceVariant,
                    )
                }
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = d.chromeWithMiniPlayer),
        ) {
            item(key = "meta") {
                Column(Modifier.padding(horizontal = d.screenPadding)) {
                    Text(
                        listOfNotNull(
                            "${books.size} book" + if (books.size == 1) "" else "s",
                            author?.let { "by $it" },
                            formatSpan(totalDuration).takeIf { totalDuration > 0 },
                        ).joinToString("  ·  "),
                        style = Fukuro.type.body,
                        color = c.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (currentBook != null) item(key = "hero") {
                val p = progress[currentBook.id]
                val started = p != null && (p.isFinished || p.progress > 0.001)
                HeroCard(
                    overline = if (started) "Reading now" else "Up next",
                    title = currentBook.media.metadata.title ?: currentBook.relPath,
                    subtitle = listOfNotNull(
                        "Book ${currentIndex + 1}".takeIf { currentIndex >= 0 },
                        formatTimeLeft(timeLeftSeconds(currentBook, p)),
                    ).joinToString("  ·  "),
                    progress = p?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                    cover = cachedCover(currentBook.id),
                    onOpen = { onOpenBook(currentBook.id) },
                    onPlay = { onOpenBook(currentBook.id) },
                    modifier = Modifier.padding(
                        start = d.screenPadding, end = d.screenPadding, top = 12.dp,
                    ),
                )
            }

            item(key = "progress") {
                Column {
                    ShelfTitle("Series progress")
                    Column(Modifier.padding(horizontal = d.screenPadding)) {
                        SegmentedProgressBar(row.segments)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${row.readCount} of ${books.size} finished",
                                style = Fukuro.type.captionMeta,
                                color = c.onSurfaceVariant,
                            )
                            Text(
                                "${formatSpan((totalDuration - listened).coerceAtLeast(0.0))} left",
                                style = Fukuro.type.captionMeta,
                                color = c.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item(key = "actions") {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = d.screenPadding, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(d.chipGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeriesAction("Pin", onPin) {
                        Icon(
                            Icons.AutoMirrored.Rounded.AddToHomeScreen, null,
                            Modifier.size(16.dp), tint = c.onBackground,
                        )
                    }
                    when {
                        busy -> SeriesAction("$downloadedCount/${books.size}", {}) {
                            CircularProgressIndicator(
                                Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent,
                            )
                        }
                        downloadedCount < books.size -> SeriesAction("Download", onDownloadAll) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(16.dp), tint = c.onBackground)
                        }
                    }
                    if (!busy && downloadedCount > 0) SeriesAction("Remove", onRemoveDownloads) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp), tint = c.onBackground)
                    }
                }
            }

            item(key = "books-title") { ShelfTitle("Books") }

            itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                SeriesBookRow(
                    index = index,
                    book = book,
                    progress = progress[book.id],
                    cover = cachedCover(book.id),
                    playing = book.id == playingBookId,
                    onOpen = { onOpenBook(book.id) },
                    modifier = Modifier
                        .padding(horizontal = d.screenPadding)
                        .padding(bottom = if (index == books.lastIndex) 0.dp else d.rowGap),
                )
            }
        }
    }
}

/** One book in the series list: its number, cover, state and progress. */
@Composable
private fun SeriesBookRow(
    index: Int,
    book: LibraryItem,
    progress: MediaProgress?,
    cover: Any?,
    playing: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    val fraction = when {
        progress?.isFinished == true -> 1f
        progress != null -> progress.progress.toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
    Row(
        modifier.fillMaxWidth().height(d.rowHeight).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.rowContentGap),
    ) {
        Box {
            FlatCover(cover, book.media.metadata.title, Modifier.width(d.rowCoverWidth).height(d.rowCoverHeight))
            if (progress?.isFinished == true) {
                FinishedTick(Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    book.media.metadata.title ?: book.relPath,
                    style = Fukuro.type.rowTitle,
                    color = if (playing) c.accent else c.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Book ${index + 1}",
                    style = Fukuro.type.captionMeta,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                listOfNotNull(
                    when {
                        progress?.isFinished == true -> "Finished"
                        fraction > 0.001f -> "${(fraction * 100).toInt()}% · ${formatTimeLeft(timeLeftSeconds(book, progress))}"
                        else -> "Not started"
                    },
                    formatSpan(book.media.duration).takeIf { book.media.duration > 0 },
                ).joinToString("  ·  "),
                style = Fukuro.type.body,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TrackBar(fraction, height = d.coverProgress)
        }
    }
}

/** Pill action with an icon, matching the chips used everywhere else. */
@Composable
private fun SeriesAction(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val c = Fukuro.colors
    Row(
        Modifier.clip(CircleShape).background(c.surface)
            .border(1.dp, c.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = Fukuro.dims.chipPaddingH, vertical = Fukuro.dims.chipPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon()
        Text(label, style = Fukuro.type.chip, color = c.onBackground, maxLines = 1)
    }
}
