package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private enum class StatsPeriod(val label: String) {
    WEEK("7 days"), MONTH("30 days"), YEAR("This year"), ALL("All time")
}

private data class DisplaySession(
    val id: String,
    val itemId: String,
    val title: String,
    val author: String,
    val seconds: Double,
    val startedAt: Long,
    val updatedAt: Long,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun StatsScreen(vm: ShelfViewModel, onOpenBook: (String) -> Unit) {
    val state by vm.state.collectAsState()
    val localDays by vm.store.listeningDaysFlow.collectAsState(initial = emptyMap())
    val localSessions by vm.store.listeningSessionsFlow.collectAsState(initial = emptyList())
    val cachedServerStats by vm.store.serverListeningStatsFlow.collectAsState(initial = null)
    val cachedServerSessions by vm.store.serverListeningSessionsFlow.collectAsState(initial = emptyList())
    var liveServerStats by remember { mutableStateOf<ListeningStats?>(null) }
    var liveServerSessions by remember { mutableStateOf<List<ListeningSession>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshRequest by remember { mutableStateOf(0) }
    var pullRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(state.loggedIn, state.serverOnline, refreshRequest) {
        if (!state.loggedIn || !state.serverOnline) {
            pullRefreshing = false
            return@LaunchedEffect
        }
        while (true) {
            loading = pullRefreshing || (liveServerStats == null && cachedServerStats == null)
            loadFailed = false
            try {
                val stats = vm.api.listeningStats()
                // A wider history is needed for yearly author/series totals. Only five
                // sessions are ever rendered in the Recent Sessions section below.
                val sessions = vm.api.listeningSessions(1000)
                liveServerStats = stats
                liveServerSessions = sessions
                vm.store.cacheServerListeningStats(stats, sessions)
            } catch (_: Exception) {
                loadFailed = true
            } finally {
                loading = false
                pullRefreshing = false
            }
            delay(60_000)
        }
    }

    val serverStats = liveServerStats ?: cachedServerStats
    val serverSessions = liveServerSessions ?: cachedServerSessions

    val allDays = remember(serverStats, localDays) {
        buildMap<String, Double> {
            serverStats?.days?.forEach { (day, seconds) -> put(day, seconds) }
            localDays.forEach { (day, seconds) -> put(day, (get(day) ?: 0.0) + seconds) }
        }
    }
    val sessions = remember(serverSessions, localSessions, state.allItems) {
        val remote = serverSessions.map {
            DisplaySession(
                id = it.id,
                itemId = it.libraryItemId,
                title = it.displayTitle.ifBlank {
                    state.allItems.firstOrNull { item -> item.id == it.libraryItemId }
                        ?.media?.metadata?.title.orEmpty()
                },
                author = it.displayAuthor,
                seconds = it.timeListening,
                startedAt = it.startedAt,
                updatedAt = it.updatedAt,
            )
        }
        val local = localSessions.map {
            DisplaySession(it.id, it.itemId, it.title, it.author, it.timeListening, it.startedAt, it.updatedAt)
        }
        (remote + local).distinctBy { it.id }.sortedByDescending { it.updatedAt }
    }

    var period by remember { mutableStateOf(StatsPeriod.WEEK) }
    var chartStyle by rememberSaveable { mutableStateOf(ChartStyle.BARS) }
    val today = LocalDate.now()
    val selectedDays = remember(allDays, period, today) {
        allDays.mapNotNull { (raw, seconds) -> parseDay(raw)?.let { it to seconds } }
            .filter { (day, _) -> inPeriod(day, period, today) }
            .toMap()
    }
    // Today stands outside the period filter: it is always today.
    val todaySeconds = allDays.entries.firstOrNull { parseDay(it.key) == today }?.value ?: 0.0
    val listeningSeconds = selectedDays.values.sum()
    val activeDays = selectedDays.count { it.value > 0.0 }
    val finishedProgress = state.progress.values.filter { progress ->
        progress.isFinished && epochDay(progress.lastUpdate)?.let { inPeriod(it, period, today) } == true
    }
    val completedItems = finishedProgress.mapNotNull { progress ->
        state.allItems.firstOrNull { it.id == progress.libraryItemId }
    }
    val finished = completedItems.size
    // Reading progress is a lifetime total, so it ignores the period chips.
    val allTimeCompleted = state.progress.values
        .filter { it.isFinished }
        .mapNotNull { p -> state.allItems.firstOrNull { it.id == p.libraryItemId } }
    val allTimeCompletedSeconds = allTimeCompleted.sumOf { it.media.duration }
    val current = state.progress.values
        .filter { !it.isFinished && it.progress > 0.001 }
        .maxByOrNull { it.lastUpdate }
    val currentItem = current?.let { p -> state.allItems.firstOrNull { it.id == p.libraryItemId } }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Fukuro.colors.background,
        contentColor = Fukuro.colors.onBackground,
    ) {
      Column(Modifier.fillMaxSize()) {
        FlatTopBar("Stats")
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        PullToRefreshBox(
            isRefreshing = loading || state.loading,
            onRefresh = {
                pullRefreshing = true
                refreshRequest += 1
                vm.refresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp
                ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatsPeriod.entries.forEach { choice ->
                            FukuroChip(choice.label, period == choice, { period = choice })
                        }
                    }
                }

                item { TodayTile(todaySeconds) }

                if (!state.loggedIn && allDays.isEmpty()) item {
                    EmptyStats("Start listening in Fukuro to build your stats. Sign in to include your Audiobookshelf history.")
                } else if (!state.serverOnline && allDays.isEmpty()) item {
                    EmptyStats("Connect to your Audiobookshelf server to load listening history.")
                } else if (loadFailed && allDays.isEmpty()) item {
                    EmptyStats("Listening history could not be loaded. Your local stats will still be recorded.")
                }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile(formatDuration(listeningSeconds), "Listening", Modifier.weight(1f))
                    SummaryTile(finished.toString(), "Finished", Modifier.weight(1f))
                    SummaryTile(activeDays.toString(), "Active days", Modifier.weight(1f))
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Listening activity", Modifier.weight(1f))
                    ChartStyleButton(
                        icon = Icons.Rounded.BarChart,
                        description = "Show as bars",
                        selected = chartStyle == ChartStyle.BARS,
                    ) { chartStyle = ChartStyle.BARS }
                    Spacer(Modifier.width(4.dp))
                    ChartStyleButton(
                        icon = Icons.Rounded.ShowChart,
                        description = "Show as a line",
                        selected = chartStyle == ChartStyle.LINE,
                    ) { chartStyle = ChartStyle.LINE }
                }
                Spacer(Modifier.height(10.dp))
                ListeningChart(chartBuckets(selectedDays, period, today), chartStyle)
            }

            item {
                SectionTitle("Reading progress")
                SectionCaption("All time")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile(allTimeCompleted.size.toString(), "Books completed", Modifier.weight(1f))
                    SummaryTile(formatDuration(allTimeCompletedSeconds), "Story completed", Modifier.weight(1f))
                }
            }

            item {
                SectionTitle("Listening habits")
                Spacer(Modifier.height(10.dp))
                val activeAverage = if (activeDays == 0) 0.0 else listeningSeconds / activeDays
                val allActive = allDays.mapNotNull { (d, s) -> parseDay(d)?.takeIf { s > 0 } }.toSet()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile(formatDuration(activeAverage), "Daily average", Modifier.weight(1f))
                    SummaryTile("${currentStreak(allActive, today)} days", "Current streak", Modifier.weight(1f))
                    SummaryTile("${longestStreak(allActive)} days", "Longest streak", Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                ContributionGrid(allDays, today)
            }

            if (current != null && currentItem != null) item {
                SectionTitle("Currently listening")
                Spacer(Modifier.height(10.dp))
                FlatSurface(
                    Modifier.fillMaxWidth().clickable { onOpenBook(current.libraryItemId) },
                    RoundedCornerShape(Fukuro.dims.tileRadius),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FlatCover(
                            vm.coverModel(current.libraryItemId),
                            currentItem.media.metadata.title,
                            Modifier.size(68.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                currentItem.media.metadata.title ?: currentItem.relPath,
                                style = Fukuro.type.rowTitle,
                                color = Fukuro.colors.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                currentItem.media.metadata.authorName.orEmpty(),
                                style = Fukuro.type.body,
                                color = Fukuro.colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(9.dp))
                            TrackBar(current.progress.toFloat().coerceIn(0f, 1f))
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "${(current.progress * 100).roundToInt()}% complete",
                                style = Fukuro.type.captionMeta,
                                color = Fukuro.colors.tertiaryText,
                            )
                        }
                    }
                }
            }

            if (completedItems.isNotEmpty()) item {
                CompletionHighlights(completedItems)
            }

            item {
                val recent = sessions.filter { session ->
                    session.seconds >= 15 * 60 &&
                        epochDay(session.startedAt)?.let { inPeriod(it, period, today) } == true
                }
                RecentSessionsSection(recent, onOpenBook)
            }

        }
      }
    }
}
}

@Composable
private fun SummaryTile(value: String, label: String, modifier: Modifier = Modifier) {
    val c = Fukuro.colors
    FlatSurface(modifier, RoundedCornerShape(Fukuro.dims.tileRadius)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                value,
                style = Fukuro.type.greeting,
                color = c.onBackground,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(label, style = Fukuro.type.captionMeta, color = c.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** How full one day's square is, on a 0..4 scale like GitHub's contribution graph. */
private fun heatLevel(seconds: Double, max: Double): Int = when {
    seconds <= 0.0 || max <= 0.0 -> 0
    else -> kotlin.math.ceil((seconds / max) * 4.0).toInt().coerceIn(1, 4)
}

/**
 * A year of listening as a grid of days — a column per week, a row per weekday,
 * shaded by how long was listened. Scrolls horizontally and starts at today.
 * Always the last 52 weeks: the period chips above belong to the graph, and a
 * year is what makes the shape of a habit visible.
 */
@Composable
private fun ContributionGrid(days: Map<String, Double>, today: LocalDate) {
    val c = Fukuro.colors
    val cell = 11.dp
    val gap = 3.dp
    val weeks = 52

    val byDate = remember(days) {
        days.mapNotNull { (raw, seconds) -> parseDay(raw)?.let { it to seconds } }.toMap()
    }
    // the Monday of the week 51 weeks back, so the last column is this week
    val start = remember(today) {
        today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
    }
    val max = remember(byDate, start) {
        byDate.filterKeys { !it.isBefore(start) && !it.isAfter(today) }
            .values.maxOrNull() ?: 0.0
    }
    val scroll = rememberScrollState()
    LaunchedEffect(weeks) { scroll.scrollTo(scroll.maxValue) }

    Column {
        OverlineText("The last year")
        Spacer(Modifier.height(8.dp))
        Row {
            // weekday labels stay put while the grid scrolls, as GitHub's do
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Spacer(Modifier.height(cell))
                (1..7).forEach { weekday ->
                    Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                        Text(
                            if (weekday % 2 == 1) {
                                java.time.DayOfWeek.of(weekday)
                                    .getDisplayName(TextStyle.NARROW, Locale.getDefault())
                            } else "",
                            style = Fukuro.type.captionMeta,
                            color = c.tertiaryText,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(weeks) { week ->
                    val weekStart = start.plusWeeks(week.toLong())
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        // a month's name sits above the week its first day falls in
                        Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                            val startsMonth = (0..6).any { weekStart.plusDays(it.toLong()).dayOfMonth == 1 }
                            if (startsMonth) Text(
                                weekStart.plusDays(6).month
                                    .getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                style = Fukuro.type.captionMeta,
                                color = c.tertiaryText,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        repeat(7) { weekday ->
                            val date = weekStart.plusDays(weekday.toLong())
                            if (date.isAfter(today)) {
                                Spacer(Modifier.size(cell))
                            } else {
                                val level = heatLevel(byDate[date] ?: 0.0, max)
                                Box(
                                    Modifier.size(cell)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            if (level == 0) c.track
                                            else c.accent.copy(alpha = 0.25f + 0.25f * level)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Less", style = Fukuro.type.captionMeta, color = c.tertiaryText)
            Spacer(Modifier.width(6.dp))
            (0..4).forEach { level ->
                Box(
                    Modifier.size(cell).clip(RoundedCornerShape(2.dp))
                        .background(
                            if (level == 0) c.track else c.accent.copy(alpha = 0.25f + 0.25f * level)
                        )
                )
                Spacer(Modifier.width(gap))
            }
            Spacer(Modifier.width(3.dp))
            Text("More", style = Fukuro.type.captionMeta, color = c.tertiaryText)
        }
    }
}

@Composable
private fun CompletionHighlights(books: List<LibraryItem>) {
    val authors = books.mapNotNull { it.media.metadata.authorName?.takeIf(String::isNotBlank) }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
    val narrators = books.mapNotNull { it.media.metadata.narratorName?.takeIf(String::isNotBlank) }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
    val genres = books.flatMap { it.media.metadata.genres }.filter(String::isNotBlank)
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
    if (authors.isEmpty() && narrators.isEmpty() && genres.isEmpty()) return
    SectionTitle("Completion highlights")
    SectionCaption("Ranked by books finished, so longer books do not get an advantage")
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (authors.isNotEmpty()) RankingCard("Authors", authors)
        if (narrators.isNotEmpty()) RankingCard("Narrators", narrators)
        if (genres.isNotEmpty()) RankingCard("Genres", genres)
    }
}

@Composable
private fun RankingCard(title: String, values: List<Map.Entry<String, Int>>) {
    val c = Fukuro.colors
    FlatSurface(Modifier.fillMaxWidth(), RoundedCornerShape(Fukuro.dims.tileRadius)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlineText(title)
            values.forEachIndexed { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${index + 1}",
                        style = Fukuro.type.rowTitle,
                        color = c.accent,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(
                        entry.key,
                        Modifier.weight(1f),
                        style = Fukuro.type.body,
                        color = c.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${entry.value} ${if (entry.value == 1) "book" else "books"}",
                        style = Fukuro.type.captionMeta,
                        color = c.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private enum class ChartStyle { BARS, LINE }

/**
 * Listening per bucket, as bars or a line.
 *
 * There is no y-axis column: it cost 37dp of the width and pushed the plot off
 * centre for three labels nobody reads precisely. The peak is stated once above
 * the plot instead, and the gridlines carry the rest.
 */
@Composable
private fun ListeningChart(buckets: List<Pair<String, Double>>, style: ChartStyle) {
    val c = Fukuro.colors
    val max = buckets.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    val barGap = 3.dp
    var selectedBucket by remember(buckets, style) { mutableStateOf<Int?>(null) }
    val selectedColor = lerp(c.accent, Color.Black, 0.28f)

    FlatSurface(Modifier.fillMaxWidth(), RoundedCornerShape(Fukuro.dims.tileRadius)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                selectedBucket?.let { index ->
                    buckets.getOrNull(index)?.let { (label, seconds) ->
                        Text(
                            "$label  ·  ${formatExactDuration(seconds)}",
                            style = Fukuro.type.captionMeta,
                            color = c.onAccent,
                            maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(c.accent)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Peak ${formatCompactDuration(max)}",
                    style = Fukuro.type.captionMeta,
                    color = c.tertiaryText,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(Fukuro.dims.chartHeight)) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(3) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                    }
                }
                when (style) {
                    ChartStyle.BARS -> Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(barGap),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        buckets.forEachIndexed { index, (_, seconds) ->
                            Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .clickable {
                                        selectedBucket = if (selectedBucket == index) null else index
                                    },
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    Modifier.fillMaxWidth(0.7f)
                                        .fillMaxHeight((seconds / max).toFloat().coerceIn(0.025f, 1f))
                                        .clip(
                                            RoundedCornerShape(
                                                topStart = Fukuro.dims.chartBarRadius,
                                                topEnd = Fukuro.dims.chartBarRadius,
                                            )
                                        )
                                        .background(
                                            when {
                                                seconds <= 0 -> c.track
                                                selectedBucket == index -> selectedColor
                                                else -> c.accent
                                            }
                                        )
                                )
                            }
                        }
                    }
                    ChartStyle.LINE -> LineChart(
                        values = buckets.map { it.second },
                        max = max,
                        color = c.accent,
                        selectedColor = selectedColor,
                        selectedIndex = selectedBucket,
                        onSelect = { index ->
                            selectedBucket = if (selectedBucket == index) null else index
                        },
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(barGap)) {
                buckets.forEachIndexed { index, (label, _) ->
                    val show = buckets.size <= 12 || index == 0 || index == buckets.lastIndex || index % 5 == 0
                    Text(
                        if (show) label else "",
                        modifier = Modifier.weight(1f),
                        style = Fukuro.type.captionMeta,
                        color = c.tertiaryText,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One of the two chart-shape buttons in the card's top-right corner. */
@Composable
private fun ChartStyleButton(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Fukuro.colors
    Box(
        Modifier.size(30.dp)
            .clip(RoundedCornerShape(Fukuro.dims.coverRadius))
            .background(if (selected) c.accent else c.background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            Modifier.size(18.dp),
            tint = if (selected) c.onAccent else c.tertiaryText,
        )
    }
}

/** The same buckets as a polyline, with a dot on each reading. */
@Composable
private fun LineChart(
    values: List<Double>,
    max: Double,
    color: Color,
    selectedColor: Color,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
) {
    if (values.isEmpty()) return
    Canvas(
        Modifier.fillMaxSize().pointerInput(values) {
            detectTapGestures { tap ->
                val index = if (values.size == 1) 0 else
                    ((tap.x / size.width) * (values.size - 1)).roundToInt()
                        .coerceIn(values.indices)
                onSelect(index)
            }
        }
    ) {
        val stepX = if (values.size == 1) 0f else size.width / (values.size - 1)
        fun pointAt(index: Int): Offset {
            val fraction = (values[index] / max).toFloat().coerceIn(0f, 1f)
            val x = if (values.size == 1) size.width / 2f else stepX * index
            return Offset(x, size.height * (1f - fraction))
        }
        val path = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // a dot per reading, but only while they are far enough apart to be legible
        if (values.size <= 14) {
            values.indices.forEach { index ->
                drawCircle(
                    color = if (selectedIndex == index) selectedColor else color,
                    radius = if (selectedIndex == index) 5.dp.toPx() else 3.dp.toPx(),
                    center = pointAt(index),
                )
            }
        } else if (selectedIndex != null && selectedIndex in values.indices) {
            drawCircle(selectedColor, radius = 5.dp.toPx(), center = pointAt(selectedIndex))
        }
    }
}

@Composable
private fun SessionRow(session: DisplaySession, onClick: () -> Unit) {
    val c = Fukuro.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fukuro.dims.tileRadius))
            .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoStories, null, tint = c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title.ifBlank { "Audiobook" },
                style = Fukuro.type.rowTitle,
                color = c.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val date = epochDay(session.startedAt)?.format(DateTimeFormatter.ofPattern("d MMM")) ?: ""
            Text(
                listOf(session.author, date).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = Fukuro.type.body,
                color = c.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            formatDuration(session.seconds),
            style = Fukuro.type.rowTitle,
            color = c.onBackground,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 76.dp),
        )
    }
}

@Composable
private fun RecentSessionsSection(sessions: List<DisplaySession>, onOpenBook: (String) -> Unit) {
    SectionTitle("Recent sessions")
    SectionCaption("Sessions shorter than 15 minutes are hidden")
    Spacer(Modifier.height(8.dp))
    if (sessions.isEmpty()) {
        SectionCaption("No sessions over 15 minutes in this period.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Newest-first and capped at five: when a sixth arrives, take(5) naturally drops
        // the previous oldest row and puts the new one at the top.
        sessions.take(5).forEach { session ->
            SessionRow(session) { if (session.itemId.isNotBlank()) onOpenBook(session.itemId) }
        }
    }
}

@Composable
private fun EmptyStats(text: String) {
    val c = Fukuro.colors
    FlatSurface(Modifier.fillMaxWidth(), RoundedCornerShape(Fukuro.dims.tileRadius)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BarChart, null, tint = c.accent, modifier = Modifier.size(Fukuro.dims.icon))
            Spacer(Modifier.width(12.dp))
            Text(text, style = Fukuro.type.body, color = c.onSurfaceVariant)
        }
    }
}

private fun parseDay(raw: String): LocalDate? = runCatching { LocalDate.parse(raw) }.getOrNull()
private fun epochDay(ms: Long): LocalDate? = ms.takeIf { it > 0 }?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun inPeriod(day: LocalDate, period: StatsPeriod, today: LocalDate): Boolean = when (period) {
    StatsPeriod.WEEK -> !day.isBefore(today.minusDays(6)) && !day.isAfter(today)
    StatsPeriod.MONTH -> !day.isBefore(today.minusDays(29)) && !day.isAfter(today)
    StatsPeriod.YEAR -> day.year == today.year
    StatsPeriod.ALL -> !day.isAfter(today)
}

private fun chartBuckets(days: Map<LocalDate, Double>, period: StatsPeriod, today: LocalDate): List<Pair<String, Double>> = when (period) {
    StatsPeriod.WEEK -> (6 downTo 0).map { offset ->
        val day = today.minusDays(offset.toLong())
        day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) to (days[day] ?: 0.0)
    }
    StatsPeriod.MONTH -> (29 downTo 0).map { offset ->
        val day = today.minusDays(offset.toLong())
        day.dayOfMonth.toString() to (days[day] ?: 0.0)
    }
    StatsPeriod.YEAR -> (1..12).map { month ->
        java.time.Month.of(month).getDisplayName(TextStyle.NARROW, Locale.getDefault()) to
            days.filterKeys { it.year == today.year && it.monthValue == month }.values.sum()
    }
    StatsPeriod.ALL -> {
        val years = (days.keys.map { it.year } + today.year).distinct().sorted()
        years.map { year -> year.toString() to days.filterKeys { it.year == year }.values.sum() }
    }
}

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 60 * 24 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
    }
}

/** Tooltip duration that always retains minute precision, including multi-day totals. */
private fun formatExactDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt().coerceAtLeast(0)
    val days = minutes / 1440
    val hours = (minutes % 1440) / 60
    val mins = minutes % 60
    return buildList {
        if (days > 0) add("${days}d")
        if (hours > 0) add("${hours}h")
        add("${mins}m")
    }.joinToString(" ")
}

private fun formatCompactDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h"
}

private fun currentStreak(active: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in active) today else today.minusDays(1)
    var count = 0
    while (day in active) { count++; day = day.minusDays(1) }
    return count
}

private fun longestStreak(active: Set<LocalDate>): Int {
    if (active.isEmpty()) return 0
    var best = 1
    var run = 1
    val sorted = active.sorted()
    for (i in 1 until sorted.size) {
        run = if (sorted[i] == sorted[i - 1].plusDays(1)) run + 1 else 1
        if (run > best) best = run
    }
    return best
}


/**
 * Today's listening, in its own small tile above the period sections — it is the
 * one figure that should not move when the period chips change.
 */
@Composable
private fun TodayTile(seconds: Double) {
    val c = Fukuro.colors
    FlatSurface(Modifier.fillMaxWidth(), RoundedCornerShape(Fukuro.dims.tileRadius)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            OverlineText("Listened today")
            Spacer(Modifier.height(4.dp))
            Text(
                if (seconds > 0) formatDuration(seconds) else "Nothing yet",
                style = Fukuro.type.statValue,
                color = c.onBackground,
                maxLines = 1,
            )
        }
    }
}
