package fukuro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* ---------------------------------------------------------------------------
 * Settings → Customise home
 *
 * The user's shelves, in the order Home draws them: drag to reorder, switch one
 * off without losing it, tap to edit it against a live preview of real data.
 * Built from the same atoms as Home so the screen belongs to the app rather than
 * looking like a preferences page.
 * ------------------------------------------------------------------------- */

private val SHELF_ROW_HEIGHT = 68.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomiseHomeScreen(
    vm: ShelfViewModel,
    onBack: () -> Unit,
    miniPlayerVisible: Boolean = false,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    val state by vm.state.collectAsState()
    val stored by vm.store.homeShelvesFlow.collectAsState(initial = emptyList())
    val customShelf by vm.store.customShelfFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // A working copy, so a drag can reorder at 60fps without a round trip through
    // DataStore. Followed by an effect rather than remember(stored) on purpose: the
    // state object has to stay the same instance for the life of the screen, because
    // the drag gesture below holds on to it across recompositions.
    var shelves by remember { mutableStateOf(emptyList<Shelf>()) }
    LaunchedEffect(stored) { shelves = stored }
    var editing by remember { mutableStateOf<Shelf?>(null) }
    var addingSource by remember { mutableStateOf(false) }
    var pickingItems by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun persist(list: List<Shelf>) {
        shelves = list
        scope.launch { vm.store.setHomeShelves(list) }
    }

    fun delete(shelf: Shelf) {
        val index = shelves.indexOfFirst { it.id == shelf.id }
        if (index < 0) return
        persist(shelves.filterNot { it.id == shelf.id })
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "Removed “${shelf.title}”",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                persist(shelves.toMutableList().apply { add(index.coerceAtMost(size), shelf) })
            }
        }
    }

    Scaffold(
        containerColor = c.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { FlatTopBar("Customise home", onBack) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize()
                .padding(top = pad.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(bottom = d.chromeHeight(miniPlayerVisible)),
        ) {
            SectionCaption(
                "Home draws these top to bottom. Drag a handle to reorder them.",
                Modifier.padding(horizontal = d.screenPadding, vertical = 4.dp),
            )
            Spacer(Modifier.height(12.dp))

            ShelfReorderList(
                shelves = shelves,
                // a drag reorders on screen straight away and is written once, on release
                onMove = { from, to ->
                    shelves = shelves.toMutableList().apply { add(to, removeAt(from)) }
                },
                onCommit = { persist(shelves) },
                onToggle = { shelf, on ->
                    persist(shelves.map { if (it.id == shelf.id) it.copy(enabled = on) else it })
                },
                onEdit = { editing = it },
                onDelete = { delete(it) },
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = d.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(d.chipGap),
            ) {
                FukuroChip(
                    label = "Add shelf",
                    selected = true,
                    onClick = { addingSource = true },
                    leading = {
                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp), tint = c.onAccent)
                    },
                )
                FukuroChip("Reset to defaults", false, { confirmReset = true })
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { shelf ->
        ShelfEditorSheet(
            vm = vm,
            state = state,
            customShelf = customShelf,
            shelf = shelf,
            onDismiss = { editing = null },
            onSave = { updated ->
                persist(shelves.map { if (it.id == updated.id) updated else it })
                editing = null
            },
            onEditItems = { pickingItems = true },
        )
    }

    if (pickingItems) {
        CustomShelfEditorDialog(
            vm = vm,
            current = customShelf,
            onDismiss = { pickingItems = false },
            onSave = { entries ->
                scope.launch { vm.store.setCustomShelf(entries) }
                pickingItems = false
            },
        )
    }

    if (addingSource) {
        SourcePickerSheet(
            state = state,
            onDismiss = { addingSource = false },
            onPick = { source, title ->
                addingSource = false
                val shelf = Shelf(
                    id = newShelfId(),
                    title = title,
                    source = source,
                    layout = defaultLayoutFor(source),
                    sort = defaultSortFor(source),
                )
                persist(shelves + shelf)
                editing = shelf
            },
        )
    }

    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = c.surface,
            title = { Text("Reset home shelves?", color = c.onBackground) },
            text = {
                Text(
                    "Puts back the three shelves Fukuro ships with. Your books, " +
                        "progress and favourites are untouched.",
                    style = Fukuro.type.body,
                    color = c.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    shelves = DEFAULT_SHELVES
                    scope.launch { vm.store.resetHomeShelves() }
                }) { Text("Reset", color = c.accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text("Cancel", color = c.onSurfaceVariant)
                }
            },
        )
    }
}

/**
 * The shelf list with hand-rolled drag-to-reorder.
 *
 * The list is short by nature — these are shelves, not books — so it is a plain
 * Column: the dragged row can then translate over its neighbours, and the rows
 * swap under it as it passes them. No drag-and-drop dependency for one screen.
 */
@Composable
private fun ShelfReorderList(
    shelves: List<Shelf>,
    onMove: (from: Int, to: Int) -> Unit,
    onCommit: () -> Unit,
    onToggle: (Shelf, Boolean) -> Unit,
    onEdit: (Shelf) -> Unit,
    onDelete: (Shelf) -> Unit,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    val rowHeightPx = with(LocalDensity.current) { SHELF_ROW_HEIGHT.toPx() }

    // The gesture below outlives the composition that created it, so it reads the
    // list and the callbacks through these rather than capturing them directly.
    val currentShelves by rememberUpdatedState(shelves)
    val move by rememberUpdatedState(onMove)
    val commit by rememberUpdatedState(onCommit)

    // -1 = nothing being dragged. The index moves with the row as it is dragged,
    // so the gesture keeps working after the list has been reordered under it.
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    if (shelves.isEmpty()) {
        SectionCaption(
            "No shelves yet — add one below.",
            Modifier.padding(horizontal = d.screenPadding, vertical = 12.dp),
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        shelves.forEachIndexed { index, shelf ->
            key(shelf.id) {
                val dragging = index == dragIndex
                Row(
                    Modifier.fillMaxWidth()
                        .height(SHELF_ROW_HEIGHT)
                        .zIndex(if (dragging) 1f else 0f)
                        .offset { IntOffset(0, if (dragging) dragOffset.roundToInt() else 0) }
                        .padding(horizontal = d.screenPadding)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (dragging) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The handle drags on touch rather than after a long press: it is
                    // already a deliberate target, and waiting for a long press meant
                    // competing with the page's own scroll for the same gesture.
                    Box(
                        Modifier.size(d.touchTarget).pointerInput(shelf.id) {
                            detectDragGestures(
                                onDragStart = {
                                    dragIndex = currentShelves.indexOfFirst { it.id == shelf.id }
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    val from = dragIndex
                                    if (from < 0) return@detectDragGestures
                                    dragOffset += amount.y
                                    val steps = (dragOffset / rowHeightPx).roundToInt()
                                    val to = (from + steps).coerceIn(0, currentShelves.lastIndex)
                                    if (to == from) {
                                        // at an end of the list: don't let the row drift off it
                                        dragOffset = dragOffset.coerceIn(-rowHeightPx, rowHeightPx)
                                        return@detectDragGestures
                                    }
                                    move(from, to)
                                    dragOffset -= (to - from) * rowHeightPx
                                    dragIndex = to
                                },
                                onDragEnd = {
                                    dragIndex = -1
                                    dragOffset = 0f
                                    commit()
                                },
                                onDragCancel = {
                                    dragIndex = -1
                                    dragOffset = 0f
                                    commit()
                                },
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            "Reorder ${shelf.title}",
                            Modifier.size(d.icon),
                            tint = if (dragging) c.accent else c.tertiaryText,
                        )
                    }
                    // tapping the shelf itself edits it; the handle keeps the drag
                    Column(Modifier.weight(1f).clickable { onEdit(shelf) }) {
                        Text(
                            shelf.title,
                            style = Fukuro.type.rowTitle,
                            color = if (shelf.enabled) c.onBackground else c.tertiaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(
                                sourceLabel(shelf.source),
                                if (shelf.layout == ShelfLayout.CAROUSEL) "Carousel" else "Rows",
                                shelf.maxItems?.let { "$it max" } ?: "All",
                            ).joinToString("  ·  "),
                            style = Fukuro.type.captionMeta,
                            color = c.tertiaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = shelf.enabled,
                        onCheckedChange = { on -> onToggle(shelf, on) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.onAccent,
                            checkedTrackColor = c.accent,
                        ),
                    )
                    IconButton(onClick = { onDelete(shelf) }) {
                        Icon(
                            Icons.Rounded.Delete, "Remove ${shelf.title}",
                            Modifier.size(20.dp), tint = c.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Editing one shelf
 * ------------------------------------------------------------------------- */

/** The slider runs 1..10 and then one step past it, which means "no limit". */
private const val MAX_ITEMS_SLIDER_TOP = 11

private fun sliderToMaxItems(position: Float): Int? =
    position.roundToInt().let { if (it >= MAX_ITEMS_SLIDER_TOP) null else it.coerceAtLeast(1) }

private fun maxItemsToSlider(maxItems: Int?): Float =
    (maxItems ?: MAX_ITEMS_SLIDER_TOP).coerceIn(1, MAX_ITEMS_SLIDER_TOP).toFloat()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfEditorSheet(
    vm: ShelfViewModel,
    state: UiState,
    customShelf: List<CustomShelfEntry>,
    shelf: Shelf,
    onDismiss: () -> Unit,
    onSave: (Shelf) -> Unit,
    onEditItems: () -> Unit,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    var draft by remember(shelf.id) { mutableStateOf(shelf) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.screenPadding)
                .padding(bottom = 32.dp),
        ) {
            SectionTitle("Edit shelf")
            Spacer(Modifier.height(4.dp))
            SectionCaption(sourceLabel(draft.source))

            Spacer(Modifier.height(16.dp))
            OverlineText("Title")
            Spacer(Modifier.height(8.dp))
            FlatTextField(
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                placeholder = "Shelf title",
            )

            Spacer(Modifier.height(16.dp))
            OverlineText("Layout")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = listOf(
                    ShelfLayout.CAROUSEL to "Carousel",
                    ShelfLayout.ROWS to "Rows",
                ),
                selected = draft.layout,
                onSelect = { draft = draft.copy(layout = it) },
            )
            if (draft.source == ShelfSource.AllSeries) {
                Spacer(Modifier.height(6.dp))
                SectionCaption("Series always draw as rows, so their progress fits.")
            }

            Spacer(Modifier.height(16.dp))
            OverlineText("Order")
            Spacer(Modifier.height(8.dp))
            ChipGroup(
                options = SHELF_SORT_LABELS.toList(),
                selected = draft.sort,
                onSelect = { draft = draft.copy(sort = it) },
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OverlineText("Show at most", Modifier.weight(1f))
                Text(
                    draft.maxItems?.let { "$it item" + (if (it == 1) "" else "s") } ?: "No limit",
                    style = Fukuro.type.chip,
                    color = c.onBackground,
                )
            }
            Slider(
                value = maxItemsToSlider(draft.maxItems),
                onValueChange = { draft = draft.copy(maxItems = sliderToMaxItems(it)) },
                valueRange = 1f..MAX_ITEMS_SLIDER_TOP.toFloat(),
                // one detent per whole number, so the value is picked exactly
                steps = MAX_ITEMS_SLIDER_TOP - 2,
            )

            if (draft.source == ShelfSource.CustomList) {
                Spacer(Modifier.height(16.dp))
                OverlineText("Items")
                Spacer(Modifier.height(8.dp))
                FukuroChip(
                    label = if (customShelf.isEmpty()) "Choose items"
                    else "Choose items (${customShelf.size})",
                    selected = false,
                    onClick = { onEditItems() },
                )
            }

            Spacer(Modifier.height(20.dp))
            OverlineText("Preview")
            Spacer(Modifier.height(8.dp))
            ShelfPreview(vm, state, customShelf, draft)

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(d.chipGap)) {
                FukuroChip("Save", true, { onSave(draft.copy(title = draft.title.trim().ifBlank { shelf.title })) })
                FukuroChip("Cancel", false, onDismiss)
            }
        }
    }
}

/** The shelf as Home would draw it, with the real library behind it. */
@Composable
private fun ShelfPreview(
    vm: ShelfViewModel,
    state: UiState,
    customShelf: List<CustomShelfEntry>,
    shelf: Shelf,
) {
    val c = Fukuro.colors
    // Previews are capped at a few items: this is a sample, not the shelf itself.
    val sample = remember(shelf, state.allItems, state.series, customShelf) {
        resolveShelf(shelf.copy(maxItems = (shelf.maxItems ?: 4).coerceAtMost(4)),
            state, customShelf, HomeFilter.ALL)
    }
    FlatSurface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Text(
                shelf.title.ifBlank { "Untitled shelf" },
                style = Fukuro.type.shelfTitle,
                color = c.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            when (sample) {
                is ShelfItems.Books ->
                    if (effectiveLayout(shelf, sample) == ShelfLayout.CAROUSEL) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(Fukuro.dims.carouselGap),
                        ) {
                            items(sample.books, key = { it.id }) { book ->
                                val p = state.progress[book.id]
                                CarouselCell(
                                    title = book.media.metadata.title ?: book.relPath,
                                    meta = book.media.metadata.authorName,
                                    cover = vm.coverModel(book.id),
                                    progress = p?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                                    finished = p?.isFinished == true,
                                    coverSize = state.coverSize,
                                    onClick = {},
                                )
                            }
                        }
                    } else {
                        Column(
                            Modifier.padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(Fukuro.dims.rowGap),
                        ) {
                            sample.books.take(3).forEach { book ->
                                val p = state.progress[book.id]
                                BookProgressRow(
                                    title = book.media.metadata.title ?: book.relPath,
                                    meta = book.media.metadata.authorName.orEmpty(),
                                    cover = vm.coverModel(book.id),
                                    progress = p?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f,
                                    onClick = {},
                                )
                            }
                        }
                    }

                is ShelfItems.SeriesGroups -> Column(
                    Modifier.padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Fukuro.dims.rowGap),
                ) {
                    sample.series.take(2).forEach { series ->
                        val data = seriesRowData(series, state.progress)
                        SeriesProgressRow(
                            title = series.name,
                            readCount = data.readCount,
                            totalCount = data.total,
                            nextUp = data.nextUp,
                            cover = data.nextBookId?.let { vm.coverModel(it) },
                            segments = data.segments,
                            onClick = {},
                        )
                    }
                }

                else -> Text(
                    when (sample) {
                        is ShelfItems.AuthorCards -> "${sample.authors.size} author cards"
                        is ShelfItems.NarratorCards -> "${sample.narrators.size} narrator cards"
                        is ShelfItems.CustomEntries -> "${sample.entries.size} hand-picked items"
                        else -> "Nothing to show yet — this shelf is hidden on Home until it has something in it."
                    },
                    style = Fukuro.type.body,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Adding a shelf
 * ------------------------------------------------------------------------- */

/** Sources that need a second question, and the label for that question. */
private enum class SourceKind(val label: String, val prompt: String) {
    SERIES("One series", "Which series?"),
    GENRE("A genre", "Which genre?"),
    AUTHOR("An author", "Which author?"),
    LIBRARY("A library", "Which library?"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePickerSheet(
    state: UiState,
    onDismiss: () -> Unit,
    onPick: (ShelfSource, String) -> Unit,
) {
    val c = Fukuro.colors
    val d = Fukuro.dims
    var kind by remember { mutableStateOf<SourceKind?>(null) }
    var query by remember { mutableStateOf("") }

    // id (or name) to display title, for whichever second question is open
    val choices: List<Pair<String, String>> = remember(kind, state.allItems, state.series, query) {
        when (kind) {
            SourceKind.SERIES -> state.series.map { it.id to it.name }
            SourceKind.GENRE -> state.allItems.flatMap { it.media.metadata.genres }
                .filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }.map { it to it }
            SourceKind.AUTHOR -> (state.authors.map { it.name } + state.allItems.flatMap { authorsOf(it) })
                .filter { it.isNotBlank() }.distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }.map { it to it }
            SourceKind.LIBRARY -> state.libraries.map { it.id to it.name }
            null -> emptyList()
        }.filter { query.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.background) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp)
                .padding(horizontal = d.screenPadding).padding(bottom = 32.dp),
        ) {
            SectionTitle(kind?.prompt ?: "Add a shelf")
            Spacer(Modifier.height(12.dp))

            if (kind == null) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(SIMPLE_SHELF_SOURCES, key = { sourceLabel(it) }) { source ->
                        PickerRow(sourceLabel(source)) { onPick(source, sourceLabel(source)) }
                    }
                    items(
                        SourceKind.entries.filter { it != SourceKind.LIBRARY || state.libraries.isNotEmpty() },
                        key = { it.name },
                    ) { entry ->
                        PickerRow(entry.label) { kind = entry; query = "" }
                    }
                }
            } else {
                FlatTextField(query, { query = it }, "Search")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(choices, key = { it.first }) { (id, title) ->
                        PickerRow(title) {
                            val source = when (kind) {
                                SourceKind.SERIES -> ShelfSource.SingleSeries(id)
                                SourceKind.GENRE -> ShelfSource.Genre(id)
                                SourceKind.AUTHOR -> ShelfSource.Author(id)
                                else -> ShelfSource.Library(id)
                            }
                            onPick(source, title)
                        }
                    }
                    if (choices.isEmpty()) item {
                        SectionCaption(
                            if (query.isBlank()) "Nothing here yet" else "No matches",
                            Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                FukuroChip("Back", false, { kind = null; query = "" })
            }
        }
    }
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = Fukuro.type.rowTitle,
        color = Fukuro.colors.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    )
}

/* ---------------------------------------------------------------------------
 * The hand-picked shelf
 * ------------------------------------------------------------------------- */

private val CUSTOM_SHELF_TYPES = listOf(
    "book" to "Books",
    "series" to "Series",
    "author" to "Authors",
    "narrator" to "Narrators",
)

private fun customShelfTypeLabel(type: String): String =
    CUSTOM_SHELF_TYPES.firstOrNull { it.first == type }?.second?.removeSuffix("s") ?: type

/** Edit one ordered list whose cards can point at any supported library entity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomShelfEditorDialog(
    vm: ShelfViewModel,
    current: List<CustomShelfEntry>,
    onDismiss: () -> Unit,
    onSave: (List<CustomShelfEntry>) -> Unit,
) {
    val c = Fukuro.colors
    val state by vm.state.collectAsState()
    var selected by remember(current) { mutableStateOf(current) }
    var type by remember { mutableStateOf("book") }
    var query by remember { mutableStateOf("") }

    val allCandidates = when (type) {
        "series" -> state.series.map { CustomShelfEntry("series", it.id, it.name) }
        "author" -> (state.authors.map { it.name } + state.allItems.flatMap { authorsOf(it) })
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
            .map { CustomShelfEntry("author", it, it) }
        "narrator" -> state.allItems.flatMap { narratorsOf(it) }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
            .map { CustomShelfEntry("narrator", it, it) }
        else -> state.allItems
            .sortedBy { it.media.metadata.titleIgnorePrefix ?: it.media.metadata.title }
            .map { CustomShelfEntry("book", it.id, it.media.metadata.title ?: "Untitled") }
    }
    val candidates = allCandidates
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .filterNot { candidate -> selected.any { it.type == candidate.type && it.id == candidate.id } }
        .take(80)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { SectionTitle("Hand-picked shelf") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                item {
                    SectionCaption("These appear on Home in this exact order.")
                    Spacer(Modifier.height(8.dp))
                }
                if (selected.isEmpty()) {
                    item {
                        SectionCaption("Nothing selected yet")
                        Spacer(Modifier.height(8.dp))
                    }
                }
                itemsIndexed(selected, key = { _, entry -> "selected:${entry.type}:${entry.id}" }) { index, entry ->
                    Row(
                        Modifier.fillMaxWidth().height(52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.title,
                                style = Fukuro.type.body,
                                color = c.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                customShelfTypeLabel(entry.type),
                                style = Fukuro.type.captionMeta,
                                color = c.tertiaryText,
                            )
                        }
                        IconButton(
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                            onClick = {
                                selected = selected.toMutableList().also {
                                    val moved = it.removeAt(index); it.add(index - 1, moved)
                                }
                            },
                        ) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = c.onSurfaceVariant) }
                        IconButton(
                            enabled = index < selected.lastIndex,
                            modifier = Modifier.size(32.dp),
                            onClick = {
                                selected = selected.toMutableList().also {
                                    val moved = it.removeAt(index); it.add(index + 1, moved)
                                }
                            },
                        ) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = c.onSurfaceVariant) }
                        IconButton(
                            modifier = Modifier.size(32.dp),
                            onClick = { selected = selected - entry },
                        ) { Icon(Icons.Rounded.Delete, "Remove", tint = c.onSurfaceVariant) }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    OverlineText("Add items")
                    Spacer(Modifier.height(8.dp))
                    ChipGroup(
                        options = CUSTOM_SHELF_TYPES,
                        selected = type,
                        onSelect = { type = it; query = "" },
                    )
                    Spacer(Modifier.height(8.dp))
                    FlatTextField(query, { query = it }, "Search")
                    Spacer(Modifier.height(4.dp))
                }
                items(candidates, key = { "candidate:${it.type}:${it.id}" }) { entry ->
                    Text(
                        entry.title,
                        style = Fukuro.type.body,
                        color = c.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selected = selected + entry }
                            .padding(vertical = 12.dp),
                    )
                }
                if (candidates.isEmpty()) {
                    item {
                        SectionCaption(
                            if (query.isBlank()) "No more items available" else "No matches",
                            Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("Save", color = c.accent) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.onSurfaceVariant) }
        },
    )
}
