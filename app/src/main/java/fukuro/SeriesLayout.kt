package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.request.ImageRequest
import kotlin.math.abs

private val SeriesShape = RoundedCornerShape(28.dp)
private val SeriesFill = Color(0x52101010)
private val SeriesBorder = Color(0x2EFFFFFF)
private val SeriesText = Color(0xFFF6F3F0)
private val SeriesMutedText = Color(0xB8F6F3F0)

/**
 * A cover-led background that gives the series page its colour without
 * hard-coding a palette. Compose blur is accelerated on Android 12+; the dark scrims
 * keep the unblurred fallback readable on older versions.
 */
@Composable
fun SeriesBackdrop(model: Any?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The background is heavily blurred, so decoding a full 400px cover only adds
    // memory/GPU work without adding visible detail.
    val backdropRequest = remember(model, context) {
        model?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(160, 240)
                .crossfade(false)
                .build()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CoverImage(
            model = backdropRequest,
            contentDescription = null,
            // Only the upper page needs cover colour; the design fades to black below.
            // Limiting the blur layer avoids rasterising the entire scrolling screen.
            modifier = Modifier.fillMaxWidth().height(560.dp).graphicsLayer {
                scaleX = 1.16f
                scaleY = 1.16f
            }.blur(36.dp),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x80000000),
                    0.34f to Color(0x9A050505),
                    0.72f to Color(0xE6090909),
                    1f to Color.Black,
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0x70000000), Color.Transparent, Color(0x70000000))
                )
            )
        )
        content()
    }
}

@Composable
fun SeriesSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier.clip(SeriesShape)
            .background(SeriesFill)
            .border(1.dp, SeriesBorder, SeriesShape)
            .padding(contentPadding)
    ) { content() }
}

/** Cover-led series overview with aggregate progress and a book-by-book journey. */
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
    val coverCache = remember(books, downloadedCount) { HashMap<String, Any?>() }
    val cachedCover: (String) -> Any? = { id ->
        if (!coverCache.containsKey(id)) coverCache[id] = coverModel(id)
        coverCache[id]
    }
    val currentBook = books.firstOrNull { it.id == playingBookId } ?: books.firstOrNull { book ->
        progress[book.id]?.let { !it.isFinished && it.progress > 0.001 } == true
    } ?: books.firstOrNull { progress[it.id]?.isFinished != true } ?: books.lastOrNull()
    val backdrop = currentBook?.let { cachedCover(it.id) }
        ?: books.firstOrNull()?.let { cachedCover(it.id) }
    val completedCount = books.count { progress[it.id]?.isFinished == true }
    val totalDuration = books.sumOf { it.media.duration.coerceAtLeast(0.0) }
    val listened = books.sumOf { book ->
        val p = progress[book.id]
        when {
            p?.isFinished == true -> book.media.duration
            p != null -> p.currentTime.coerceIn(0.0, book.media.duration.coerceAtLeast(0.0))
            else -> 0.0
        }
    }
    val overallProgress = if (totalDuration > 0.0) (listened / totalDuration).toFloat().coerceIn(0f, 1f) else 0f
    // Keep the path readable when a series contains a very short intro/bonus item,
    // while still making every segment reflect its book's share of the runtime.
    val minimumSegmentDuration = (totalDuration * 0.025).coerceAtLeast(1.0)
    val author = books.firstNotNullOfOrNull { it.media.metadata.authorName?.takeIf(String::isNotBlank) }
    val accent = MaterialTheme.colorScheme.primary

    SeriesBackdrop(backdrop) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 156.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SeriesCircleAction(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SeriesText)
                    }
                    Text(
                        series.name.uppercase(),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        color = SeriesMutedText,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    SeriesCircleAction(onClick = onToggleFavorite) {
                        Icon(
                            if (allFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            if (allFavorite) "Remove series from favorites" else "Add series to favorites",
                            tint = if (allFavorite) accent else SeriesText,
                        )
                    }
                }
            }

            item { SeriesCoverFan(books, currentBook?.id, cachedCover) }

            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${books.size} BOOK SERIES",
                        color = accent,
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        series.name,
                        color = SeriesText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    if (author != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "by $author",
                            color = SeriesMutedText,
                            style = MaterialTheme.typography.titleMedium,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "$completedCount of ${books.size} completed  •  ${seriesDuration(totalDuration)} total",
                        color = SeriesMutedText,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                SeriesSurface(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            SeriesSectionLabel("PATH TRAVELED")
                            Text(
                                "${(overallProgress * 100).toInt()}% of series",
                                color = SeriesMutedText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().height(40.dp),
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            books.forEachIndexed { index, book ->
                                val p = progress[book.id]
                                val durationWeight = book.media.duration
                                    .takeIf { it.isFinite() && it > 0.0 }
                                    ?.coerceAtLeast(minimumSegmentDuration)
                                    ?.toFloat()
                                    ?: minimumSegmentDuration.toFloat()
                                val fraction = when {
                                    p?.isFinished == true -> 1f
                                    p != null -> p.progress.toFloat().coerceIn(0f, 1f)
                                    else -> 0f
                                }
                                Box(
                                    Modifier.weight(durationWeight).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                                        .background(Color(0x24FFFFFF))
                                ) {
                                    if (fraction > 0f) Box(
                                        Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent)
                                    )
                                    Text(
                                        "${index + 1}",
                                        modifier = Modifier.align(Alignment.Center),
                                        color = if (fraction > 0.55f) Color.Black else SeriesText,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${seriesDuration(listened)} listened", color = SeriesMutedText,
                                style = MaterialTheme.typography.bodySmall)
                            Text("${seriesDuration((totalDuration - listened).coerceAtLeast(0.0))} left",
                                color = SeriesMutedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (currentBook != null) {
                item {
                    CurrentSeriesBookCard(
                        book = currentBook,
                        progress = progress[currentBook.id],
                        cover = cachedCover(currentBook.id),
                        accent = accent,
                        onOpen = { onOpenBook(currentBook.id) },
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeriesActionButton(onPin, Modifier.weight(1f), "Pin") {
                        Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, null, Modifier.size(18.dp), tint = SeriesText)
                    }
                    if (busy) {
                        SeriesSurface(Modifier.weight(1f), PaddingValues(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                                Text("$downloadedCount/${books.size}", color = SeriesText)
                            }
                        }
                    } else if (downloadedCount < books.size) {
                        SeriesActionButton(onDownloadAll, Modifier.weight(1f), "Download") {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp), tint = SeriesText)
                        }
                    }
                    if (!busy && downloadedCount > 0) {
                        SeriesActionButton(onRemoveDownloads, Modifier.weight(1f), "Remove") {
                            Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = SeriesText)
                        }
                    }
                }
            }

            item { SeriesSectionLabel("JOURNEY") }

            itemsIndexed(books, key = { _, book -> book.id }) { index, book ->
                JourneyBookRow(
                    index = index,
                    book = book,
                    progress = progress[book.id],
                    cover = cachedCover(book.id),
                    accent = accent,
                    isLast = index == books.lastIndex,
                    onOpen = { onOpenBook(book.id) },
                )
            }
        }
    }
}

@Composable
private fun SeriesCoverFan(
    books: List<LibraryItem>,
    activeBookId: String?,
    coverModel: (String) -> Any?,
) {
    val maxCovers = 7
    val activeIndex = books.indexOfFirst { it.id == activeBookId }.coerceAtLeast(0)
    val firstShown = if (books.size <= maxCovers) 0 else {
        (activeIndex - maxCovers / 2).coerceIn(0, books.size - maxCovers)
    }
    val shown = books.drop(firstShown).take(maxCovers)
    BoxWithConstraints(Modifier.fillMaxWidth().height(190.dp)) {
        if (shown.isEmpty()) return@BoxWithConstraints
        val coverWidth = 112.dp
        val stride = if (shown.size == 1) 0.dp else (maxWidth - coverWidth) / (shown.size - 1).toFloat()
        val center = (shown.lastIndex / 2f)
        shown.forEachIndexed { index, book ->
            val distance = abs(index - center)
            val bookIndex = firstShown + index
            // The playing book is the peak of the fan. Each cover one book farther
            // away is drawn one layer lower, in both directions through the series.
            val stackLayer = -abs(bookIndex - activeIndex).toFloat()
            CoverImage(
                model = coverModel(book.id),
                contentDescription = book.media.metadata.title,
                modifier = Modifier.width(coverWidth).aspectRatio(2f / 3f)
                    .zIndex(stackLayer)
                    .offset(x = stride * index.toFloat(), y = (distance * 7).dp)
                    .graphicsLayer { rotationZ = (index - center) * 4f }
                    .shadow(12.dp, RoundedCornerShape(7.dp))
                    .clip(RoundedCornerShape(7.dp))
                    .border(1.dp, SeriesBorder, RoundedCornerShape(7.dp)),
            )
        }
    }
}

@Composable
private fun CurrentSeriesBookCard(
    book: LibraryItem,
    progress: MediaProgress?,
    cover: Any?,
    accent: Color,
    onOpen: () -> Unit,
) {
    SeriesSurface(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                cover,
                book.media.metadata.title,
                Modifier.width(112.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SeriesSectionLabel("READING NOW")
                Text(
                    book.media.metadata.title ?: book.relPath,
                    color = SeriesText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val fraction = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = accent,
                    trackColor = Color(0x2EFFFFFF),
                )
                Text(
                    "${(fraction * 100).toInt()}%  •  ${seriesDuration(book.media.duration * (1.0 - fraction.toDouble()))} left",
                    color = SeriesMutedText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = onOpen,
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (fraction > 0f) "Resume" else "Start")
                }
            }
        }
    }
}

@Composable
private fun JourneyBookRow(
    index: Int,
    book: LibraryItem,
    progress: MediaProgress?,
    cover: Any?,
    accent: Color,
    isLast: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(26.dp).height(104.dp), contentAlignment = Alignment.TopCenter) {
            if (!isLast) Box(
                Modifier.width(2.dp).fillMaxHeight().offset(y = 14.dp).background(Color(0x38FFFFFF))
            )
            val done = progress?.isFinished == true
            Box(
                Modifier.size(if (done) 22.dp else 18.dp).clip(CircleShape)
                    .background(if (done || (progress?.progress ?: 0.0) > 0.001) accent else Color(0xFF242424))
                    .border(2.dp, if (done) accent else SeriesMutedText, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (done) "✓" else "${index + 1}", color = if (done) Color.Black else SeriesText,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        CoverImage(
            cover,
            book.media.metadata.title,
            Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                book.media.metadata.title ?: book.relPath,
                color = SeriesText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Book ${index + 1}  •  ${seriesDuration(book.media.duration)}",
                color = SeriesMutedText,
                style = MaterialTheme.typography.bodySmall,
            )
            val fraction = progress?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f
            val stateLabel = when {
                progress?.isFinished == true -> "Completed"
                fraction > 0.001f -> "${(fraction * 100).toInt()}% complete"
                else -> "Not started"
            }
            Text(stateLabel, color = if (fraction > 0f || progress?.isFinished == true) accent else SeriesMutedText,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SeriesCircleAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(SeriesFill)
            .border(1.dp, SeriesBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) { IconButton(onClick = onClick, content = content) }
}

@Composable
private fun SeriesActionButton(
    onClick: () -> Unit,
    modifier: Modifier,
    label: String,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier.clip(CircleShape).background(SeriesFill).border(1.dp, SeriesBorder, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(label, color = SeriesText, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SeriesSectionLabel(text: String) {
    Text(
        text,
        color = SeriesMutedText,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun seriesDuration(seconds: Double): String {
    val totalMinutes = (seconds.coerceAtLeast(0.0) / 60.0).toLong()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
