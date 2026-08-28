package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Standard action shape. Pills are reserved for choices, not buttons. */
val FukuroButtonShape = RoundedCornerShape(10.dp)

/* ---------------------------------------------------------------------------
 * Shared atoms
 *
 * Everything Home is built from, so the same card, chip, cover cell and
 * progress bar can be reused by Library, Series and Settings without any of
 * them re-deriving a size or a colour. Nothing here reads a view model: the
 * screens pass plain values and lambdas.
 * ------------------------------------------------------------------------- */

/** Carousel cell width per cover-size setting; index 2 (M, the default) is the design's 96dp. */
private val CAROUSEL_CELL_WIDTHS = listOf(72, 84, 96, 116, 140)

fun carouselCellWidth(coverSize: Int): Dp = CAROUSEL_CELL_WIDTHS[coverSize.coerceIn(0, 4)].dp

/** Covers keep the design's 96:108 cell proportions at every size. */
fun carouselCoverHeight(coverSize: Int): Dp = (CAROUSEL_CELL_WIDTHS[coverSize.coerceIn(0, 4)] * 108 / 96).dp

/** "6h 51m left" — the shape the frames use under a cover and in the hero. */
fun formatTimeLeft(seconds: Double): String = "${formatSpan(seconds)} left"

/** "6h 51m", or "51m" for anything under an hour. Minutes are padded next to hours. */
fun formatSpan(seconds: Double): String {
    val totalMinutes = (seconds.coerceAtLeast(0.0) / 60.0).toLong()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
}

/**
 * "Chapter 14" for the book's position, or null when the server sent no chapter
 * list — which it does not for plain library listings, only for a fetched item.
 */
fun chapterLabel(item: LibraryItem, currentTime: Double): String? {
    val chapters = item.media.chapters
    if (chapters.isEmpty()) return null
    val index = chapters.indexOfLast { it.start <= currentTime + 0.001 }
    return "Chapter ${(if (index < 0) 0 else index) + 1}"
}

/** Seconds still to listen to, from a progress record and the book's own duration. */
fun timeLeftSeconds(item: LibraryItem, progress: MediaProgress?): Double {
    val duration = item.media.duration.takeIf { it > 0.0 } ?: progress?.duration ?: 0.0
    val done = progress?.currentTime ?: 0.0
    return (duration - done).coerceAtLeast(0.0)
}

/** A flat card: theme surface, 1dp hairline, no elevation and no tint. */
@Composable
fun FlatSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Fukuro.dims.heroRadius),
    content: @Composable () -> Unit,
) {
    val c = Fukuro.colors
    Box(modifier.clip(shape).background(c.surface).border(1.dp, c.outline, shape)) { content() }
}

/** The title above a shelf, with the design's gaps baked in. */
@Composable
fun ShelfTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Fukuro.type.shelfTitle,
        color = Fukuro.colors.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(
            start = Fukuro.dims.screenPadding,
            end = Fukuro.dims.screenPadding,
            top = Fukuro.dims.shelfTitleTop,
            bottom = Fukuro.dims.shelfTitleBottom,
        ),
    )
}

/** Small uppercase label, e.g. "READING NOW". */
@Composable
fun OverlineText(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Fukuro.type.overline,
        color = Fukuro.colors.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * Pill chip. Selected is an accent fill; unselected is a surface with a hairline.
 * Used everywhere the app offers a choice, so Material's own chip never appears.
 */
@Composable
fun FukuroChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Fukuro.colors
    val shape = CircleShape
    Row(
        modifier
            .clip(shape)
            .background(if (selected) c.accent else c.surface)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.outline, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = Fukuro.dims.chipPaddingH, vertical = Fukuro.dims.chipPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        leading?.invoke()
        Text(
            label,
            style = if (selected) Fukuro.type.chipSelected else Fukuro.type.chip,
            color = if (selected) c.onAccent else c.onBackground,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        trailing?.invoke()
    }
}

/** A row of chips where exactly one option is picked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun <T> ChipGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.chipGap),
        verticalArrangement = Arrangement.spacedBy(Fukuro.dims.chipGap),
    ) {
        options.forEach { (value, label) ->
            FukuroChip(
                label, value == selected, { onSelect(value) },
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            )
        }
    }
}

/**
 * Flat top bar for pushed screens: page background, no elevation, no divider —
 * the title carries the hierarchy rather than a bar.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FlatTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val c = Fukuro.colors
    androidx.compose.material3.TopAppBar(
        title = {
            Text(
                title,
                style = Fukuro.type.greeting,
                color = c.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (onBack != null) androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = c.onBackground)
            }
        },
        actions = actions,
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
            containerColor = c.background,
            titleContentColor = c.onBackground,
            navigationIconContentColor = c.onBackground,
            actionIconContentColor = c.onSurfaceVariant,
        ),
    )
}

/**
 * Single-line text field in the flat language: a surface with a hairline, no
 * Material label animation and no filled container.
 */
@Composable
fun FlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = Fukuro.colors
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.fillMaxWidth().height(FukuroDims.touchTarget)
            .clip(shape).background(c.surface).border(1.dp, c.outline, shape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Fukuro.type.chip.copy(color = c.onBackground),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, style = Fukuro.type.chip, color = c.onSurfaceVariant)
                }
                inner()
            },
        )
    }
}

/** Section heading inside Settings and other stacked pages. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Fukuro.type.shelfTitle, color = Fukuro.colors.onBackground, modifier = modifier)
}

/** Explanatory line under a heading or a control. */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Fukuro.type.body, color = Fukuro.colors.onSurfaceVariant, modifier = modifier)
}

/** Dot + label pill, used for server status in the Home header. */
@Composable
fun StatusPill(
    label: String,
    dotColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Fukuro.colors
    Row(
        modifier
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.outline, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = Fukuro.dims.chipPaddingV),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.statusDot),
    ) {
        Box(Modifier.size(Fukuro.dims.statusDot).clip(CircleShape).background(dotColor))
        Text(label, style = Fukuro.type.navLabel, color = c.onBackground, maxLines = 1)
    }
}

/** A rounded progress bar on the theme's track colour. */
@Composable
fun TrackBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = Fukuro.dims.heroProgress,
    fill: androidx.compose.ui.graphics.Color = Fukuro.colors.accent,
    track: androidx.compose.ui.graphics.Color = Fukuro.colors.track,
) {
    val shape = RoundedCornerShape(height / 2)
    Box(modifier.fillMaxWidth().height(height).clip(shape).background(track)) {
        if (progress > 0f) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(fill))
        }
    }
}

/** One book's share of a series bar: how wide it is, and how much of it is read. */
data class ProgressSegment(val weight: Float, val fraction: Float)

/**
 * A bar with one segment per book, each weighted by that book's duration. Finished
 * books are solid accent, the one in progress is part-filled, the rest are track.
 */
@Composable
fun SegmentedProgressBar(
    segments: List<ProgressSegment>,
    modifier: Modifier = Modifier,
    height: Dp = Fukuro.dims.segmentProgress,
) {
    if (segments.isEmpty()) return
    val c = Fukuro.colors
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.segmentGap),
    ) {
        segments.forEach { segment ->
            Box(
                Modifier.weight(segment.weight.coerceAtLeast(0.05f)).fillMaxHeight()
                    .clip(shape).background(c.track),
            ) {
                if (segment.fraction > 0f) Box(
                    Modifier.fillMaxWidth(segment.fraction.coerceIn(0f, 1f)).fillMaxHeight()
                        .background(c.accent),
                )
            }
        }
    }
}

/** Book cover at the design's radius, with the app's usual placeholder. */
@Composable
fun FlatCover(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    radius: Dp = Fukuro.dims.coverRadius,
) {
    CoverImage(model, contentDescription, modifier.clip(RoundedCornerShape(radius)))
}

/** Round accent play button — the one place on Home where the accent is a fill. */
@Composable
fun AccentPlayButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = Fukuro.dims.heroPlayButton,
    isPlaying: Boolean = false,
) {
    val c = Fukuro.colors
    Box(
        modifier.size(size).clip(CircleShape).background(c.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription,
            Modifier.size(Fukuro.dims.heroPlayIcon),
            tint = c.onAccent,
        )
    }
}

/**
 * The "Reading now" card: cover, overline, title, progress and the play button.
 * Home hides it entirely when nothing is in progress.
 */
@Composable
fun HeroCard(
    title: String,
    subtitle: String,
    progress: Float,
    cover: Any?,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    overline: String = "Reading now",
    isPlaying: Boolean = false,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    FlatSurface(
        modifier.fillMaxWidth().height(d.heroHeight),
        shape = RoundedCornerShape(d.heroRadius),
    ) {
        Row(
            Modifier.fillMaxSize().clickable(onClick = onOpen).padding(d.heroPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlatCover(
                model = cover,
                contentDescription = title,
                modifier = Modifier
                    .width(d.heroCoverWidth)
                    .height(d.heroCoverHeight)
                    .shadow(
                        elevation = d.heroCoverElevation,
                        shape = RoundedCornerShape(d.heroCoverRadius),
                        ambientColor = c.coverShadow,
                        spotColor = c.coverShadow,
                    ),
                radius = d.heroCoverRadius,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OverlineText(overline)
                Text(
                    title,
                    style = Fukuro.type.heroTitle,
                    color = c.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TrackBar(progress, height = d.heroProgress)
                Text(
                    subtitle,
                    style = Fukuro.type.body,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AccentPlayButton(
                onPlay,
                if (isPlaying) "Pause $title" else "Play $title",
                isPlaying = isPlaying,
            )
        }
    }
}

/**
 * One cell of a browsing carousel: cover with a progress hairline across its bottom
 * edge, then a two-line caption.
 */
@Composable
fun CarouselCell(
    title: String,
    meta: String?,
    cover: Any?,
    progress: Float,
    finished: Boolean,
    coverSize: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    progressStyle: String = "bar",
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    Column(
        modifier
            .width(carouselCellWidth(coverSize))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().height(carouselCoverHeight(coverSize))) {
            FlatCover(cover, title, Modifier.fillMaxSize())
            if (progress > 0.001f && !finished) {
                if (progressStyle == "circle") {
                    CoverProgressRing(progress.coerceIn(0f, 1f), this)
                } else {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .height(d.coverProgress).background(c.coverProgressStrip),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                                .background(c.accent),
                        )
                    }
                }
            }
            if (finished) CoverFinishedBadge(this)
        }
        Text(
            title,
            style = Fukuro.type.captionTitle,
            color = c.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (meta != null) Text(
            meta,
            style = Fukuro.type.captionMeta,
            color = c.tertiaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small accent disc with a tick, for a book that has been finished. */
@Composable
fun FinishedTick(modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val c = Fukuro.colors
    Box(
        modifier.size(size).clip(CircleShape).background(c.accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = "Finished",
            tint = c.onAccent,
            modifier = Modifier.size(size * 0.72f),
        )
    }
}

/** Full-width row for one book: cover, title, metadata and a thin progress bar. */
@Composable
fun BookProgressRow(
    title: String,
    meta: String,
    cover: Any?,
    progress: Float,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    Row(
        modifier.fillMaxWidth().height(d.rowHeight)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.rowContentGap),
    ) {
        FlatCover(cover, title, Modifier.width(d.rowCoverWidth).height(d.rowCoverHeight))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = Fukuro.type.rowTitle,
                    color = c.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) Text(
                    trailing,
                    style = Fukuro.type.captionMeta,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                meta,
                style = Fukuro.type.body,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress > 0.001f) TrackBar(progress, height = d.coverProgress)
        }
    }
}

/**
 * Full-width row for one series: cover, name, `read / total`, what to play next,
 * and a segment per book weighted by its duration.
 */
@Composable
fun SeriesProgressRow(
    title: String,
    readCount: Int,
    totalCount: Int,
    nextUp: String,
    cover: Any?,
    segments: List<ProgressSegment>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    Row(
        modifier.fillMaxWidth().height(d.rowHeight).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.rowContentGap),
    ) {
        FlatCover(cover, title, Modifier.width(d.rowCoverWidth).height(d.rowCoverHeight))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = Fukuro.type.rowTitle,
                    color = c.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$readCount / $totalCount",
                    style = Fukuro.type.captionMeta,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                nextUp,
                style = Fukuro.type.body,
                color = c.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SegmentedProgressBar(segments)
        }
    }
}

/* ---------------------------------------------------------------------------
 * Previews
 * ------------------------------------------------------------------------- */

@Composable
private fun PreviewPage(dark: Boolean, content: @Composable () -> Unit) {
    FukuroPreviewTheme(dark) {
        Box(
            Modifier.fillMaxWidth().background(Fukuro.colors.background)
                .padding(Fukuro.dims.screenPadding),
        ) { content() }
    }
}

private val previewSegments = listOf(
    ProgressSegment(1f, 1f),
    ProgressSegment(1f, 1f),
    ProgressSegment(1.3f, 0.42f),
    ProgressSegment(1f, 0f),
    ProgressSegment(0.9f, 0f),
)

@Preview(name = "Hero · dark", widthDp = 396)
@Composable
private fun HeroCardDarkPreview() = PreviewPage(dark = true) {
    HeroCard(
        title = "The Long Winter",
        subtitle = "Chapter 14  ·  6h 51m left",
        progress = 0.42f,
        cover = null,
        onOpen = {}, onPlay = {},
    )
}

@Preview(name = "Hero · light", widthDp = 396)
@Composable
private fun HeroCardLightPreview() = PreviewPage(dark = false) {
    HeroCard(
        title = "The Long Winter",
        subtitle = "Chapter 14  ·  6h 51m left",
        progress = 0.42f,
        cover = null,
        onOpen = {}, onPlay = {},
    )
}

@Composable
private fun CarouselPreviewBody() {
    Row(horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.carouselGap)) {
        CarouselCell("Salt and Iron", "9h 04m left", null, 0.18f, false, 2, {})
        CarouselCell("A History of Quiet Places", "2h 40m left", null, 0.71f, false, 2, {})
        CarouselCell("Nightjar", "5h 30m left", null, 0.34f, false, 2, {})
    }
}

@Preview(name = "Carousel shelf · dark", widthDp = 396)
@Composable
private fun CarouselDarkPreview() = PreviewPage(dark = true) { CarouselPreviewBody() }

@Preview(name = "Carousel shelf · light", widthDp = 396)
@Composable
private fun CarouselLightPreview() = PreviewPage(dark = false) { CarouselPreviewBody() }

@Composable
private fun RowsPreviewBody() {
    Column(verticalArrangement = Arrangement.spacedBy(Fukuro.dims.rowGap)) {
        SeriesProgressRow(
            title = "The Winter Cycle", readCount = 2, totalCount = 5,
            nextUp = "Next up · Book 3, The Cartographer",
            cover = null, segments = previewSegments, onClick = {},
        )
        SeriesProgressRow(
            title = "Salt Roads", readCount = 0, totalCount = 4,
            nextUp = "Start with · Book 1, Ledger of Small Hours",
            cover = null,
            segments = listOf(
                ProgressSegment(1.1f, 0f), ProgressSegment(1f, 0f),
                ProgressSegment(1.2f, 0f), ProgressSegment(0.9f, 0f),
            ),
            onClick = {},
        )
    }
}

@Preview(name = "Rows shelf · dark", widthDp = 396)
@Composable
private fun RowsDarkPreview() = PreviewPage(dark = true) { RowsPreviewBody() }

@Preview(name = "Rows shelf · light", widthDp = 396)
@Composable
private fun RowsLightPreview() = PreviewPage(dark = false) { RowsPreviewBody() }
