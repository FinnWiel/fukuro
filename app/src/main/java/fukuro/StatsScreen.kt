package fukuro

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
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
    var serverStats by remember { mutableStateOf<ListeningStats?>(null) }
    var serverSessions by remember { mutableStateOf<List<ListeningSession>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(state.loggedIn, state.serverOnline) {
        if (!state.loggedIn || !state.serverOnline) return@LaunchedEffect
        loading = true
        loadFailed = false
        try {
            serverStats = vm.api.listeningStats()
            // A wider history is needed for yearly author/series totals. Only five
            // sessions are ever rendered in the Recent Sessions section below.
            serverSessions = vm.api.listeningSessions(1000)
        } catch (_: Exception) {
            loadFailed = true
        } finally {
            loading = false
        }
    }

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
    val today = LocalDate.now()
    val selectedDays = remember(allDays, period, today) {
        allDays.mapNotNull { (raw, seconds) -> parseDay(raw)?.let { it to seconds } }
            .filter { (day, _) -> inPeriod(day, period, today) }
            .toMap()
    }
    val listeningSeconds = selectedDays.values.sum()
    val activeDays = selectedDays.count { it.value > 0.0 }
    val finishedProgress = state.progress.values.filter { progress ->
        progress.isFinished && epochDay(progress.lastUpdate)?.let { inPeriod(it, period, today) } == true
    }
    val completedItems = finishedProgress.mapNotNull { progress ->
        state.allItems.firstOrNull { it.id == progress.libraryItemId }
    }
    val finished = completedItems.size
    val completedStorySeconds = completedItems.sumOf { it.media.duration }
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
                        FilterChip(
                            selected = period == choice,
                            onClick = { period = choice },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }

            if (!state.loggedIn && localDays.isEmpty()) item {
                EmptyStats("Start listening in Fukuro to build your stats. Sign in to include your Audiobookshelf history.")
            } else if (!state.serverOnline && localDays.isEmpty()) item {
                EmptyStats("Connect to your Audiobookshelf server to load listening history.")
            } else if (loadFailed && allDays.isEmpty()) item {
                EmptyStats("Listening history could not be loaded. Your local stats will still be recorded.")
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile(Icons.Rounded.Schedule, formatDuration(listeningSeconds), "Listening", Modifier.weight(1f))
                    SummaryTile(Icons.Rounded.CheckCircle, finished.toString(), "Finished", Modifier.weight(1f))
                    SummaryTile(Icons.Rounded.CalendarMonth, activeDays.toString(), "Active days", Modifier.weight(1f))
                }
            }

            item {
                SectionTitle("Listening activity")
                Spacer(Modifier.height(10.dp))
                ListeningChart(chartBuckets(selectedDays, period, today))
            }

            item {
                SectionTitle("Reading progress")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HabitTile("Books completed", finished.toString(), period.label.lowercase(), Modifier.weight(1f))
                    HabitTile("Story completed", formatDuration(completedStorySeconds), "audiobook length", Modifier.weight(1f))
                }
            }

            item {
                SectionTitle("Listening habits")
                Spacer(Modifier.height(10.dp))
                val activeAverage = if (activeDays == 0) 0.0 else listeningSeconds / activeDays
                val best = selectedDays.maxByOrNull { it.value }
                val allActive = allDays.mapNotNull { (d, s) -> parseDay(d)?.takeIf { s > 0 } }.toSet()
                val relevantSessions = sessions.filter { session ->
                    epochDay(session.startedAt)?.let { inPeriod(it, period, today) } == true
                }
                val commonTime = mostCommonTime(relevantSessions)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HabitTile("Daily average", formatDuration(activeAverage), "per active day", Modifier.weight(1f))
                        HabitTile("Best day", best?.key?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "—", formatDuration(best?.value ?: 0.0), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HabitTile("Current streak", currentStreak(allActive, today).toString(), "days", Modifier.weight(1f))
                        HabitTile("Longest streak", longestStreak(allActive).toString(), "days", Modifier.weight(1f))
                        HabitTile("Usually", commonTime, "listening", Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                WeekdayPattern(selectedDays)
            }

            if (current != null && currentItem != null) item {
                SectionTitle("Currently listening")
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenBook(current.libraryItemId) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(
                            vm.coverModel(current.libraryItemId),
                            currentItem.media.metadata.title,
                            Modifier.size(68.dp).clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(currentItem.media.metadata.title ?: currentItem.relPath, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(currentItem.media.metadata.authorName.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Spacer(Modifier.height(9.dp))
                            LinearProgressIndicator(
                                progress = { current.progress.toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                            )
                            Spacer(Modifier.height(5.dp))
                            Text("${(current.progress * 100).roundToInt()}% complete", style = MaterialTheme.typography.labelSmall)
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

            // Year in Review deliberately stays last, regardless of which sections above
            // have data for the selected period.
            item {
                YearInReviewCard(
                    days = allDays,
                    progress = state.progress.values.toList(),
                    sessions = sessions,
                    library = state.allItems,
                )
            }
        }
      }
    }
}

@Composable
private fun SummaryTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HabitTile(label: String, value: String, suffix: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(11.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(suffix, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun WeekdayPattern(days: Map<LocalDate, Double>) {
    val totals = (1..7).associateWith { weekday ->
        days.filterKeys { it.dayOfWeek.value == weekday }.values.sum()
    }
    val max = totals.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Column {
        Text("Weekday pattern", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            totals.forEach { (weekday, seconds) ->
                val intensity = if (seconds <= 0.0) 0.08f else (0.22f + 0.78f * (seconds / max).toFloat())
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = intensity)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (seconds > 0) formatCompactDuration(seconds) else "–",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (intensity > .55f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(java.time.DayOfWeek.of(weekday).getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall)
                }
            }
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
    Text(
        "Ranked by books finished, so longer books do not get an advantage",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (authors.isNotEmpty()) RankingCard("Authors", authors)
        if (narrators.isNotEmpty()) RankingCard("Narrators", narrators)
        if (genres.isNotEmpty()) RankingCard("Genres", genres)
    }
}

@Composable
private fun RankingCard(title: String, values: List<Map.Entry<String, Int>>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            values.forEachIndexed { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, modifier = Modifier.width(26.dp))
                    Text(entry.key, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${entry.value} ${if (entry.value == 1) "book" else "books"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ListeningChart(buckets: List<Pair<String, Double>>) {
    val max = buckets.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    val axisWidth = 42.dp
    val axisGap = 6.dp
    val barGap = 3.dp
    val axisLabels = listOf(formatCompactDuration(max), formatCompactDuration(max / 2), "0")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().height(150.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    Modifier.width(axisWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    axisLabels.forEach { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(axisGap))
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(axisGap))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        repeat(3) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)))
                        }
                    }
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(barGap),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        buckets.forEach { (_, seconds) ->
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                                Box(
                                    Modifier.fillMaxWidth(0.7f)
                                        .fillMaxHeight((seconds / max).toFloat().coerceIn(0.025f, 1f))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (seconds > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(axisWidth + axisGap + 1.dp + axisGap))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(barGap)) {
                    buckets.forEachIndexed { index, (label, _) ->
                        val show = buckets.size <= 12 || index == 0 || index == buckets.lastIndex || index % 5 == 0
                        Text(
                            if (show) label else "",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YearInReviewCard(
    days: Map<String, Double>,
    progress: List<MediaProgress>,
    sessions: List<DisplaySession>,
    library: List<LibraryItem>,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val years = remember(days) {
        (days.keys.mapNotNull { parseDay(it)?.year } + today.year)
            .distinct().sortedDescending()
    }
    var selectedYear by remember(years) { mutableStateOf(years.firstOrNull() ?: today.year) }
    var menuOpen by remember { mutableStateOf(false) }
    val yearDays = days.mapNotNull { (raw, seconds) -> parseDay(raw)?.takeIf { it.year == selectedYear }?.let { it to seconds } }.toMap()
    val yearFinished = progress.count { it.isFinished && epochDay(it.lastUpdate)?.year == selectedYear }
    val active = yearDays.count { it.value > 0 }
    val activeSet = yearDays.filterValues { it > 0 }.keys
    val yearSessions = sessions.filter { epochDay(it.startedAt)?.year == selectedYear }
    val booksById = library.associateBy { it.id }
    val authorTotals = yearSessions.groupBy { session ->
        booksById[session.itemId]?.media?.metadata?.authorName
            ?.takeIf { it.isNotBlank() }
            ?: session.author.takeIf { it.isNotBlank() }
    }.filterKeys { it != null }.mapKeys { it.key!! }.mapValues { (_, values) -> values.sumOf { it.seconds } }
    val seriesTotals = yearSessions.groupBy { session ->
        booksById[session.itemId]?.media?.metadata?.seriesName
            ?.substringBeforeLast('#')?.trim()?.takeIf { it.isNotBlank() }
    }.filterKeys { it != null }.mapKeys { it.key!! }.mapValues { (_, values) -> values.sumOf { it.seconds } }
    val topAuthor = authorTotals.maxByOrNull { it.value }
    val topSeries = seriesTotals.maxByOrNull { it.value }
    val orange = Color(0xFFFF6B35)
    val purple = Color(0xFF7B2CBF)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(purple, Color(0xFFE13C79), orange)))
            .padding(20.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FUKURO", color = Color.White.copy(alpha = .8f), fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    Text("Your year in stories", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                }
                Box {
                    Button(onClick = { menuOpen = true }) {
                        Text(selectedYear.toString())
                        Icon(Icons.Rounded.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        years.forEach { year ->
                            DropdownMenuItem(text = { Text(year.toString()) }, onClick = {
                                selectedYear = year
                                menuOpen = false
                            })
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(formatDuration(yearDays.values.sum()), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("spent listening", color = Color.White.copy(alpha = .85f))
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WrappedNumber(yearFinished.toString(), "books finished")
                WrappedNumber(active.toString(), "active days")
                WrappedNumber(longestStreak(activeSet).toString(), "best streak")
            }
            Spacer(Modifier.height(22.dp))
            WrappedHighlight("Most-listened author", topAuthor?.key)
            Spacer(Modifier.height(12.dp))
            WrappedHighlight("Most-listened series", topSeries?.key)
            Spacer(Modifier.height(14.dp))
            TextButton(
                onClick = {
                    val summary = buildString {
                        append("My $selectedYear Fukuro stats: ")
                        append("${formatDuration(yearDays.values.sum())} listening, ")
                        append("$yearFinished books finished, $active active days, ")
                        append("${longestStreak(activeSet)} day best streak.")
                        topAuthor?.let { append(" Most-listened author: ${it.key}.") }
                        topSeries?.let { append(" Most-listened series: ${it.key}.") }
                    }
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, summary)
                        },
                        "Share year in review",
                    ))
                },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("Share year")
            }
        }
    }
}

@Composable
private fun WrappedHighlight(label: String, name: String?) {
    Column {
        Text(label.uppercase(), color = Color.White.copy(alpha = .68f), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
        Text(
            name ?: "No listening yet",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WrappedNumber(value: String, label: String) {
    Column {
        Text(value, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SessionRow(session: DisplaySession, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(session.title.ifBlank { "Audiobook" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val date = epochDay(session.startedAt)?.format(DateTimeFormatter.ofPattern("d MMM")) ?: ""
            Text(listOf(session.author, date).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(formatDuration(session.seconds), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RecentSessionsSection(sessions: List<DisplaySession>, onOpenBook: (String) -> Unit) {
    SectionTitle("Recent sessions")
    Text(
        "Sessions shorter than 15 minutes are hidden",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (sessions.isEmpty()) {
        Text("No sessions over 15 minutes in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BarChart, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun mostCommonTime(sessions: List<DisplaySession>): String {
    if (sessions.isEmpty()) return "—"
    val counts = sessions.groupingBy {
        val hour = Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).hour
        when (hour) { in 5..11 -> "Morning"; in 12..17 -> "Afternoon"; else -> "Evening" }
    }.eachCount()
    return counts.maxByOrNull { it.value }?.key ?: "—"
}
