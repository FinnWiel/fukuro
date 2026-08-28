package fukuro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/* ---------------------------------------------------------------------------
 * Configurable home shelves
 *
 * Home is a list of shelves the user defines: where the books come from, how
 * they are laid out, how they are ordered and how many are shown. The model is
 * persisted as JSON by [Store]; the resolution below turns one shelf plus the
 * current [UiState] into the items to draw.
 * ------------------------------------------------------------------------- */

enum class ShelfLayout { CAROUSEL, ROWS }

enum class ShelfSort { RECENT, TITLE, AUTHOR, ADDED, PROGRESS }

val SHELF_SORT_LABELS = linkedMapOf(
    ShelfSort.RECENT to "Recently played",
    ShelfSort.ADDED to "Recently added",
    ShelfSort.TITLE to "Title",
    ShelfSort.AUTHOR to "Author",
    ShelfSort.PROGRESS to "Progress",
)

/**
 * Where a shelf's contents come from. The serial names are persisted, so they
 * are part of the stored format — rename a class freely, never a [SerialName].
 */
@Serializable
sealed interface ShelfSource {
    @Serializable @SerialName("continue")
    data object ContinueReading : ShelfSource

    @Serializable @SerialName("recently_added")
    data object RecentlyAdded : ShelfSource

    @Serializable @SerialName("favorites")
    data object Favorites : ShelfSource

    @Serializable @SerialName("downloaded")
    data object Downloaded : ShelfSource

    @Serializable @SerialName("finished")
    data object Finished : ShelfSource

    /** Every series, as rows with segmented progress. */
    @Serializable @SerialName("all_series")
    data object AllSeries : ShelfSource

    @Serializable @SerialName("all_books")
    data object AllBooks : ShelfSource

    @Serializable @SerialName("authors")
    data object Authors : ShelfSource

    @Serializable @SerialName("narrators")
    data object Narrators : ShelfSource

    /** The hand-picked mixed shelf the app has always had. */
    @Serializable @SerialName("custom")
    data object CustomList : ShelfSource

    @Serializable @SerialName("series")
    data class SingleSeries(val seriesId: String) : ShelfSource

    @Serializable @SerialName("genre")
    data class Genre(val genre: String) : ShelfSource

    @Serializable @SerialName("author")
    data class Author(val authorId: String) : ShelfSource

    @Serializable @SerialName("library")
    data class Library(val libraryId: String) : ShelfSource
}

@Serializable
data class Shelf(
    val id: String,
    val title: String,
    val source: ShelfSource,
    val layout: ShelfLayout,
    /** null = every item the source yields. */
    val maxItems: Int? = null,
    val enabled: Boolean = true,
    val sort: ShelfSort = ShelfSort.RECENT,
)

fun newShelfId(): String = UUID.randomUUID().toString()

/** The layout a source is built around, used as the default when adding a shelf. */
fun defaultLayoutFor(source: ShelfSource): ShelfLayout = when (source) {
    is ShelfSource.AllSeries -> ShelfLayout.ROWS
    else -> ShelfLayout.CAROUSEL
}

fun defaultSortFor(source: ShelfSource): ShelfSort = when (source) {
    ShelfSource.ContinueReading, ShelfSource.Finished -> ShelfSort.RECENT
    ShelfSource.RecentlyAdded -> ShelfSort.ADDED
    ShelfSource.AllBooks, ShelfSource.Authors, ShelfSource.Narrators -> ShelfSort.TITLE
    else -> ShelfSort.RECENT
}

/** Human name for a source, for the shelf list and the source picker. */
fun sourceLabel(source: ShelfSource): String = when (source) {
    ShelfSource.ContinueReading -> "Continue listening"
    ShelfSource.RecentlyAdded -> "Recently added"
    ShelfSource.Favorites -> "Favorites"
    ShelfSource.Downloaded -> "Downloaded"
    ShelfSource.Finished -> "Finished"
    ShelfSource.AllSeries -> "All series"
    ShelfSource.AllBooks -> "All books"
    ShelfSource.Authors -> "Authors"
    ShelfSource.Narrators -> "Narrators"
    ShelfSource.CustomList -> "Hand-picked shelf"
    is ShelfSource.SingleSeries -> "One series"
    is ShelfSource.Genre -> "Genre · ${source.genre}"
    is ShelfSource.Author -> "Author · ${source.authorId}"
    is ShelfSource.Library -> "Library"
}

/** Sources that need no further choice; the rest ask which series/genre/author/library. */
val SIMPLE_SHELF_SOURCES: List<ShelfSource> = listOf(
    ShelfSource.ContinueReading,
    ShelfSource.RecentlyAdded,
    ShelfSource.Favorites,
    ShelfSource.Downloaded,
    ShelfSource.Finished,
    ShelfSource.AllSeries,
    ShelfSource.AllBooks,
    ShelfSource.Authors,
    ShelfSource.Narrators,
    ShelfSource.CustomList,
)

/**
 * What a brand-new install starts with. Existing installs are migrated from their
 * saved `home_sections` order instead, so nobody loses a shelf they had arranged.
 */
val DEFAULT_SHELVES: List<Shelf> = listOf(
    Shelf("default-continue", "Jump back in", ShelfSource.ContinueReading, ShelfLayout.CAROUSEL, sort = ShelfSort.RECENT),
    Shelf("default-series", "Your series", ShelfSource.AllSeries, ShelfLayout.ROWS, sort = ShelfSort.RECENT),
    Shelf("default-recent", "Recently added", ShelfSource.RecentlyAdded, ShelfLayout.CAROUSEL, sort = ShelfSort.ADDED),
)

/** The pre-shelves section keys, mapped one-to-one onto shelves. */
private val SECTION_SOURCES: Map<String, Pair<String, ShelfSource>> = mapOf(
    "continue" to ("Jump back in" to ShelfSource.ContinueReading),
    "favorites" to ("Favorites" to ShelfSource.Favorites),
    "completed" to ("Completed" to ShelfSource.Finished),
    "downloaded" to ("Downloaded" to ShelfSource.Downloaded),
    "custom" to ("Custom" to ShelfSource.CustomList),
    "series" to ("Your series" to ShelfSource.AllSeries),
    "authors" to ("Authors" to ShelfSource.Authors),
    "narrators" to ("Narrators" to ShelfSource.Narrators),
    "all" to ("All books" to ShelfSource.AllBooks),
)

/** Turns a saved `home_sections` CSV into the shelf list it stands for. */
fun shelvesFromSections(csv: String): List<Shelf> =
    csv.split(',').map { it.trim() }.filter { it.isNotBlank() }.mapNotNull { key ->
        val (title, source) = SECTION_SOURCES[key] ?: return@mapNotNull null
        Shelf(
            id = "section-$key",
            title = title,
            source = source,
            layout = defaultLayoutFor(source),
            sort = defaultSortFor(source),
        )
    }.ifEmpty { DEFAULT_SHELVES }

/* ---------------------------------------------------------------------------
 * Filters
 * ------------------------------------------------------------------------- */

/** The chips above the shelves. A filter applies to every shelf below it. */
enum class HomeFilter(val label: String) {
    ALL("All"),
    IN_PROGRESS("In progress"),
    DOWNLOADED("Downloaded"),
    SERIES("Series"),
}

/* ---------------------------------------------------------------------------
 * Resolution
 * ------------------------------------------------------------------------- */

/** What one shelf resolved to. Rendering picks the layout; this picks the content. */
sealed interface ShelfItems {
    data class Books(val books: List<LibraryItem>) : ShelfItems
    data class SeriesGroups(val series: List<AbsSeries>) : ShelfItems
    data class AuthorCards(val authors: List<Pair<AbsAuthor, Int>>) : ShelfItems
    data class NarratorCards(val narrators: List<Pair<String, Int>>) : ShelfItems
    data class CustomEntries(val entries: List<CustomShelfEntry>) : ShelfItems
    data object Empty : ShelfItems

    val count: Int
        get() = when (this) {
            is Books -> books.size
            is SeriesGroups -> series.size
            is AuthorCards -> authors.size
            is NarratorCards -> narrators.size
            is CustomEntries -> entries.size
            Empty -> 0
        }
}

private fun bookMatchesFilter(item: LibraryItem, state: UiState, filter: HomeFilter): Boolean =
    when (filter) {
        HomeFilter.ALL -> true
        HomeFilter.IN_PROGRESS -> state.progress[item.id]
            ?.let { !it.isFinished && it.progress > 0.001 } == true
        HomeFilter.DOWNLOADED -> state.isOnDevice(item.id)
        // a book shelf has nothing to say about series
        HomeFilter.SERIES -> false
    }

private fun sortBooks(books: List<LibraryItem>, sort: ShelfSort, state: UiState): List<LibraryItem> =
    when (sort) {
        ShelfSort.RECENT -> books.sortedByDescending { state.progress[it.id]?.lastUpdate ?: 0L }
        ShelfSort.ADDED -> books.sortedByDescending { it.addedAt }
        ShelfSort.TITLE -> books.sortedBy {
            (it.media.metadata.titleIgnorePrefix ?: it.media.metadata.title ?: "").lowercase()
        }
        ShelfSort.AUTHOR -> books.sortedBy { it.media.metadata.authorName?.lowercase() ?: "￿" }
        ShelfSort.PROGRESS -> books.sortedByDescending { state.progress[it.id]?.progress ?: 0.0 }
    }

private fun sortSeries(series: List<AbsSeries>, sort: ShelfSort, state: UiState): List<AbsSeries> =
    when (sort) {
        ShelfSort.RECENT -> series.sortedByDescending { s ->
            s.books.maxOfOrNull { state.progress[it.id]?.lastUpdate ?: 0L } ?: 0L
        }
        ShelfSort.ADDED -> series.sortedByDescending { s -> s.books.maxOfOrNull { it.addedAt } ?: 0L }
        ShelfSort.TITLE -> series.sortedBy { it.name.lowercase() }
        ShelfSort.AUTHOR -> series.sortedBy { s ->
            s.books.firstNotNullOfOrNull { it.media.metadata.authorName }?.lowercase() ?: "￿"
        }
        ShelfSort.PROGRESS -> series.sortedByDescending { s ->
            s.books.count { state.progress[it.id]?.isFinished == true }.toDouble() /
                s.books.size.coerceAtLeast(1)
        }
    }

/** Series the filter keeps: in-progress or downloaded means "has such a book in it". */
private fun seriesMatchesFilter(series: AbsSeries, state: UiState, filter: HomeFilter): Boolean =
    when (filter) {
        HomeFilter.ALL, HomeFilter.SERIES -> true
        HomeFilter.IN_PROGRESS -> series.books.any {
            state.progress[it.id]?.let { p -> !p.isFinished && p.progress > 0.001 } == true
        }
        HomeFilter.DOWNLOADED -> series.books.any { state.isOnDevice(it.id) }
    }

/**
 * Resolves one shelf against the current library. Runs off the main thread — it
 * groups and sorts the whole library — so it takes plain data, not a view model.
 */
fun resolveShelf(
    shelf: Shelf,
    state: UiState,
    customShelf: List<CustomShelfEntry>,
    filter: HomeFilter,
): ShelfItems {
    if (!shelf.enabled) return ShelfItems.Empty
    val items = state.items

    fun books(list: List<LibraryItem>, sort: ShelfSort = shelf.sort): ShelfItems {
        val kept = list.filter { bookMatchesFilter(it, state, filter) }
        val ordered = sortBooks(kept, sort, state)
        val limited = shelf.maxItems?.let { ordered.take(it.coerceAtLeast(1)) } ?: ordered
        return if (limited.isEmpty()) ShelfItems.Empty else ShelfItems.Books(limited)
    }

    return when (val source = shelf.source) {
        ShelfSource.ContinueReading -> books(
            items.filter { item ->
                val p = state.progress[item.id]
                p != null && !p.isFinished && p.progress > 0.001 && item.id !in state.continueHidden
            }
        )

        ShelfSource.RecentlyAdded -> books(items)

        ShelfSource.Favorites -> books(items.filter { it.id in state.favorites })

        ShelfSource.Downloaded -> books(items.filter { state.isOnDevice(it.id) })

        ShelfSource.Finished -> books(items.filter { state.progress[it.id]?.isFinished == true })

        ShelfSource.AllBooks -> books(items)

        ShelfSource.AllSeries -> {
            // offline a series shrinks to the part of it that is on the device, and
            // drops out entirely when none of it is
            val visible = state.series
                .map { s ->
                    if (state.offline) s.copy(books = s.books.filter { state.isOnDevice(it.id) }) else s
                }
                .filter { it.books.isNotEmpty() && seriesMatchesFilter(it, state, filter) }
            val ordered = sortSeries(visible, shelf.sort, state)
            val limited = shelf.maxItems?.let { ordered.take(it.coerceAtLeast(1)) } ?: ordered
            if (limited.isEmpty()) ShelfItems.Empty else ShelfItems.SeriesGroups(limited)
        }

        is ShelfSource.SingleSeries -> {
            val series = state.series.firstOrNull { it.id == source.seriesId } ?: return ShelfItems.Empty
            if (filter == HomeFilter.SERIES) return ShelfItems.Empty
            books(series.books.filter { !state.offline || state.isOnDevice(it.id) })
        }

        is ShelfSource.Genre -> books(
            items.filter { item ->
                item.media.metadata.genres.any { it.equals(source.genre, ignoreCase = true) }
            }
        )

        is ShelfSource.Author -> books(items.filter { it.hasAuthor(source.authorId) })

        is ShelfSource.Library -> books(items.filter { it.libraryId == source.libraryId })

        ShelfSource.Authors -> {
            if (filter != HomeFilter.ALL) return ShelfItems.Empty
            val source0 = state.authors.ifEmpty {
                items.flatMap { authorsOf(it) }.groupingBy { it }.eachCount()
                    .map { (name, count) -> AbsAuthor(id = "", name = name, numBooks = count) }
                    .sortedBy { it.name.lowercase() }
            }.filter { author -> !state.offline || items.any { it.hasAuthor(author.name) } }
            val cards = source0.map { author -> author to items.count { it.hasAuthor(author.name) } }
            val limited = shelf.maxItems?.let { cards.take(it.coerceAtLeast(1)) } ?: cards
            if (limited.isEmpty()) ShelfItems.Empty else ShelfItems.AuthorCards(limited)
        }

        ShelfSource.Narrators -> {
            if (filter != HomeFilter.ALL) return ShelfItems.Empty
            val cards = items.flatMap { narratorsOf(it) }
                .groupingBy { it }.eachCount().toList().sortedBy { it.first.lowercase() }
            val limited = shelf.maxItems?.let { cards.take(it.coerceAtLeast(1)) } ?: cards
            if (limited.isEmpty()) ShelfItems.Empty else ShelfItems.NarratorCards(limited)
        }

        ShelfSource.CustomList -> {
            val visible = customShelf.filter { entry ->
                when (entry.type) {
                    "book" -> items.firstOrNull { it.id == entry.id }
                        ?.let { bookMatchesFilter(it, state, filter) } == true
                    "series" -> state.series.firstOrNull { it.id == entry.id }
                        ?.let { s ->
                            s.books.any { !state.offline || state.isOnDevice(it.id) } &&
                                seriesMatchesFilter(s, state, filter)
                        } == true
                    "author" -> filter == HomeFilter.ALL && items.any { it.hasAuthor(entry.id) }
                    "narrator" -> filter == HomeFilter.ALL && items.any { it.hasNarrator(entry.id) }
                    else -> false
                }
            }
            val limited = shelf.maxItems?.let { visible.take(it.coerceAtLeast(1)) } ?: visible
            if (limited.isEmpty()) ShelfItems.Empty else ShelfItems.CustomEntries(limited)
        }
    }
}

/**
 * A carousel of three books looks like a mistake next to a full-width row, so a
 * short carousel falls back to rows. Series always render as rows.
 */
fun effectiveLayout(shelf: Shelf, items: ShelfItems): ShelfLayout = when {
    items is ShelfItems.SeriesGroups -> ShelfLayout.ROWS
    shelf.layout == ShelfLayout.CAROUSEL && items is ShelfItems.Books && items.books.size < 4 ->
        ShelfLayout.ROWS
    else -> shelf.layout
}

/* ---------------------------------------------------------------------------
 * Series rows
 * ------------------------------------------------------------------------- */

/** Everything a series row draws, worked out once from the series and its progress. */
data class SeriesRowData(
    val readCount: Int,
    val total: Int,
    val nextUp: String,
    /** The book the row is inviting you into — whose cover it shows. */
    val nextBookId: String?,
    val segments: List<ProgressSegment>,
)

fun seriesRowData(series: AbsSeries, progress: Map<String, MediaProgress>): SeriesRowData {
    val books = series.books
    val readCount = books.count { progress[it.id]?.isFinished == true }
    val totalDuration = books.sumOf { it.media.duration.coerceAtLeast(0.0) }
    // keep a very short bonus item visible without letting it dominate the bar
    val minimumWeight = (totalDuration * 0.025).coerceAtLeast(1.0)
    val segments = books.map { book ->
        val p = progress[book.id]
        ProgressSegment(
            weight = (book.media.duration.takeIf { it.isFinite() && it > 0.0 } ?: minimumWeight)
                .coerceAtLeast(minimumWeight).toFloat(),
            fraction = when {
                p?.isFinished == true -> 1f
                p != null -> p.progress.toFloat().coerceIn(0f, 1f)
                else -> 0f
            },
        )
    }
    val nextIndex = books.indexOfFirst { progress[it.id]?.isFinished != true }
    val nextBook = books.getOrNull(nextIndex)
    val started = books.any { book ->
        progress[book.id]?.let { it.isFinished || it.progress > 0.001 } == true
    }
    val nextUp = when {
        nextBook == null -> "Series finished"
        started -> "Next up · Book ${nextIndex + 1}, ${nextBook.media.metadata.title ?: nextBook.relPath}"
        else -> "Start with · Book ${nextIndex + 1}, ${nextBook.media.metadata.title ?: nextBook.relPath}"
    }
    return SeriesRowData(readCount, books.size, nextUp, nextBook?.id ?: books.firstOrNull()?.id, segments)
}
