package fukuro

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.net.Uri
import coil.request.ImageRequest
import java.io.File

data class UiState(
    val loggedIn: Boolean = false,      // has a server session
    val offlineOnly: Boolean = false,   // chose to use the app without a server
    val scanning: Boolean = false,      // scanning the on-device folder
    val localCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val serverOnline: Boolean = false,
    val serverChecked: Boolean = false, // have we actually probed the server yet?
    val allItems: List<LibraryItem> = emptyList(),
    val series: List<AbsSeries> = emptyList(),
    val authors: List<AbsAuthor> = emptyList(),
    /** Named libraries currently available to this account. */
    val libraries: List<AbsLibrary> = emptyList(),
    /** The server library whose catalogue is currently displayed. */
    val activeLibraryId: String? = null,
    val serverProgress: Map<String, MediaProgress> = emptyMap(),
    val localProgress: Map<String, LocalProgress> = emptyMap(),
    val downloadedIds: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val continueHidden: Set<String> = emptySet(),
    val progressStyle: String = "circle", // "circle" | "bar"
    val coverSize: Int = 2, // 0..4, 2 = default
    val coverRevision: Int = 0,
    val currentUserRole: String? = null,
    val currentUserPermissions: AbsUserPermissions? = null,
    val recommendations: List<BookRecommendation> = emptyList(),
    val recommendationsLoading: Boolean = false,
    val recommendationsError: String? = null,
    val metadataMatchReviews: List<MetadataMatchReview> = emptyList(),
    val metadataMatchApplying: Boolean = false,
    val metadataMatchError: String? = null,
) {
    /** ABS admin areas are role-gated; never infer them from a hardcoded username. */
    val canOpenAdminSettings: Boolean
        get() = currentUserRole == "root" || currentUserRole == "admin"

    /**
     * Offline means the server was probed and wasn't there. Before the first probe it
     * is simply unknown, so nothing is hidden on the way in — the cached library keeps
     * showing until we know better.
     */
    val offline: Boolean get() = serverChecked && !serverOnline

    /** Only books whose audio sits on the device can be opened without the server. */
    fun isOnDevice(itemId: String) = LocalLibrary.isLocal(itemId) || itemId in downloadedIds

    /**
     * What the library shows. With the server reachable that's everything; offline it
     * is only the books that can actually be played, since the rest are dead covers.
     * Computed once per state (lazily) rather than on every read — the lists are long
     * and the screens touch [items] many times per frame.
     */
    val items: List<LibraryItem> by lazy {
        if (!offline) allItems else allItems.filter { isOnDevice(it.id) }
    }

    /**
     * Progress as the screens see it: the server's record and the device's, merged per book,
     * newest write wins. The device always has an answer, which is what keeps offline
     * listening on the shelf; the server takes over again once it has been told.
     */
    val progress: Map<String, MediaProgress> by lazy {
        mergeProgress(serverProgress, localProgress, allItems)
    }
}

/** @see UiState.progress */
private fun mergeProgress(
    server: Map<String, MediaProgress>,
    local: Map<String, LocalProgress>,
    items: List<LibraryItem>,
): Map<String, MediaProgress> {
    if (local.isEmpty()) return server
    val durations = items.associate { it.id to it.media.duration }
    val merged = server.toMutableMap()
    for ((itemId, own) in local) {
        val theirs = server[itemId]
        if (theirs != null && theirs.lastUpdate >= own.updatedAt) continue // server is current
        val duration = durations[itemId]?.takeIf { it > 0 } ?: theirs?.duration ?: 0.0
        merged[itemId] = MediaProgress(
            id = theirs?.id ?: "", // no server record yet; reset looks the id up when it needs one
            libraryItemId = itemId,
            duration = duration,
            progress = if (duration > 0) (own.pos / duration).coerceIn(0.0, 1.0) else 0.0,
            currentTime = own.pos,
            // a position well short of the end means the book is being listened to again,
            // whatever the finished flag was last set to
            isFinished = own.finished && (duration <= 0.0 || own.pos >= duration * 0.99),
            lastUpdate = own.updatedAt,
        )
    }
    return merged
}

/** Upload page state. */
data class UploadUi(val running: Boolean = false, val message: String? = null, val success: Boolean = false)

data class AdminUi(
    val runningAction: String? = null,
    val message: String? = null,
    val success: Boolean = false,
    val users: List<AbsUser> = emptyList(),
    val onlineUsers: List<AbsOnlineUser> = emptyList(),
    val openSessions: List<AbsOpenSession> = emptyList(),
)

/** Where the app has got to with a newer release. */
data class UpdateUi(
    val checking: Boolean = false,
    val info: UpdateInfo? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val file: java.io.File? = null,      // downloaded, waiting to be installed
    val needsPermission: Boolean = false, // the user has not allowed installs yet
    val upToDate: Boolean = false,        // set by a manual check that found nothing
    val error: String? = null,
    val dismissed: Boolean = false,       // banner hidden for this run
)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {
    private val shelf = ShelfApp.from(app)
    val api get() = shelf.api
    val store get() = shelf.store
    val downloads get() = shelf.downloads
    val downloadStates: kotlinx.coroutines.flow.StateFlow<Map<String, DownloadState>> get() = shelf.downloads.states
    val coverOverrides get() = shelf.coverOverrides

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _update = MutableStateFlow(UpdateUi())
    val update: StateFlow<UpdateUi> = _update

    private val _admin = MutableStateFlow(AdminUi())
    val admin: StateFlow<AdminUi> = _admin

    /** Lazy lists key on item id, and a repeat key is a hard crash — never allow one. */
    private fun List<LibraryItem>.unique() = distinctBy { it.id }

    val local get() = shelf.local
    private val cache get() = shelf.cache
    private val recommendationService get() = shelf.recommendations
    private var recommendationJob: kotlinx.coroutines.Job? = null
    private var autoMatchJob: kotlinx.coroutines.Job? = null
    private var manualReviewGate: CompletableDeferred<Boolean>? = null
    /** Reject a stale response when the user changes libraries before it finishes loading. */
    private var libraryLoadGeneration = 0L

    /** Loads the last discovery result immediately, then refreshes it when its daily cache expires. */
    fun refreshRecommendations(force: Boolean = false) {
        if (recommendationJob?.isActive == true && !force) return
        recommendationJob?.cancel()
        recommendationJob = viewModelScope.launch {
            val snapshot = _state.value
            if (snapshot.allItems.isEmpty()) return@launch
            if (snapshot.recommendations.isEmpty()) {
                val cached = recommendationService.cached()
                if (cached.isNotEmpty()) _state.value = _state.value.copy(recommendations = cached)
            }
            _state.value = _state.value.copy(
                recommendationsLoading = true,
                recommendationsError = null,
            )
            try {
                val current = _state.value
                val books = recommendationService.recommendations(
                    library = current.allItems,
                    favorites = current.favorites,
                    progress = current.progress,
                    force = force,
                )
                _state.value = _state.value.copy(
                    recommendations = books,
                    recommendationsLoading = false,
                    recommendationsError = if (books.isEmpty()) "No matches yet" else null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    recommendationsLoading = false,
                    recommendationsError = if (_state.value.recommendations.isEmpty()) {
                        e.message ?: "Could not load recommendations"
                    } else null,
                )
            }
        }
    }

    suspend fun recommendationDetails(book: BookRecommendation): BookRecommendation =
        recommendationService.details(book)

    suspend fun similarRecommendations(
        book: LibraryItem,
        force: Boolean = false,
    ): List<BookRecommendation> =
        recommendationService.similarTo(book, _state.value.allItems, force = force)

    fun dismissRecommendation(book: BookRecommendation) = viewModelScope.launch {
        store.dismissRecommendation(recommendationFeedbackKey(book))
        _state.value = _state.value.copy(
            recommendations = _state.value.recommendations.filterNot {
                recommendationFeedbackKey(it) == recommendationFeedbackKey(book)
            }
        )
    }

    fun reduceRecommendationAuthor(book: BookRecommendation) = viewModelScope.launch {
        val author = book.authors.firstOrNull() ?: return@launch
        store.reduceRecommendationAuthor(author)
        _state.value = _state.value.copy(
            recommendations = _state.value.recommendations.filterNot { candidate ->
                candidate.authors.any { it.equals(author, ignoreCase = true) }
            }
        )
    }

    fun reduceRecommendationTopic(book: BookRecommendation) = viewModelScope.launch {
        val topic = book.primaryTopic ?: return@launch
        store.reduceRecommendationTopic(topic)
        _state.value = _state.value.copy(
            recommendations = _state.value.recommendations.filterNot {
                it.primaryTopic?.equals(topic, ignoreCase = true) == true
            }
        )
    }

    fun boostRecommendation(book: BookRecommendation) = viewModelScope.launch {
        store.boostRecommendation(book.authors.firstOrNull(), book.primaryTopic)
    }

    /**
     * Cover for any book. On-device books use the file scanned out of their folder and
     * downloaded books the cover saved next to their audio — the server URL is only the
     * last resort, so a downloaded book still shows its art with no connection.
     */
    fun coverModel(itemId: String): Any? {
        coverOverrides.coverFile(itemId)?.let { return coverOverrideModel(it) }
        return when {
            LocalLibrary.isLocal(itemId) -> local.coverFile(itemId)
            // covers are resolved while composing, so only touch the disk for books that
            // are actually on it
            itemId in _state.value.downloadedIds -> downloads.localCover(itemId) ?: serverCoverModel(itemId)
            else -> serverCoverModel(itemId)
        }
    }

    /** A successful library refresh may include replaced artwork at the same server URL. */
    private fun serverCoverModel(itemId: String): String =
        "${api.coverUrl(itemId)}&revision=${_state.value.coverRevision}"

    private fun coverOverrideModel(file: File): ImageRequest =
        ImageRequest.Builder(getApplication<Application>())
            .data(file)
            .memoryCacheKey("${file.absolutePath}:${file.lastModified()}")
            .diskCacheKey("${file.absolutePath}:${file.lastModified()}")
            .build()

    init {
        viewModelScope.launch {
            val hasToken = store.token() != null && store.serverUrl() != null
            val offline = store.offlineOnlyFlow.first()

            // 1) paint immediately from disk: last server response + on-device books.
            //    No network on this path, so a dead server costs nothing.
            //    All of it off the main thread: reading the on-device library parses a
            //    file that can hold thousands of books, and listing downloads stats every
            //    audio file of every one. On the main thread that lands squarely on the
            //    first second of the process and janks the startup animation.
            val cached = cache.read()
            val (localItems, downloadedIds) = withContext(Dispatchers.IO) {
                local.items() to downloads.downloadedIds().toSet()
            }
            _state.value = _state.value.copy(
                loggedIn = hasToken,
                offlineOnly = offline,
                allItems = ((cached?.items ?: emptyList()) + localItems).unique(),
                series = cached?.series ?: emptyList(),
                authors = cached?.authors ?: emptyList(),
                activeLibraryId = cached?.libraryId?.takeIf { it.isNotBlank() },
                serverProgress = (cached?.progress ?: emptyList()).associateBy { it.libraryItemId },
                // localProgress arrives via its own collector below, which DataStore fills
                // in straight away — no disk read on the startup path
                downloadedIds = downloadedIds,
                localCount = localItems.size,
            )

            // 2) then talk to the server, if there is one
            if (hasToken) refresh()
            checkForUpdate()
        }
        // keep favorites in sync with the persisted set
        viewModelScope.launch {
            store.favoritesFlow.collect { fav -> _state.value = _state.value.copy(favorites = fav) }
        }
        viewModelScope.launch {
            store.continueHiddenFlow.collect { h -> _state.value = _state.value.copy(continueHidden = h) }
        }
        viewModelScope.launch {
            store.progressStyleFlow.collect { s -> _state.value = _state.value.copy(progressStyle = s) }
        }
        viewModelScope.launch {
            store.coverSizeFlow.collect { s -> _state.value = _state.value.copy(coverSize = s) }
        }
        // the player writes positions here as it goes, connected or not, so the shelves and
        // progress bars follow playback without waiting on the server
        viewModelScope.launch {
            store.localProgressFlow.collect { p -> _state.value = _state.value.copy(localProgress = p) }
        }
        // connection indicator + live progress: every 15s
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (_state.value.loggedIn) {
                    val wasOffline = !_state.value.serverOnline
                    val ok = api.ping()
                    // refresh just the progress map so list progress bars track live playback
                    val me = if (ok) try { api.me() } catch (_: Exception) { null } else null
                    val progress = me?.mediaProgress?.associateBy { it.libraryItemId }
                        ?: _state.value.serverProgress
                    _state.value = _state.value.copy(
                        serverOnline = ok,
                        serverChecked = true,
                        serverProgress = progress,
                        currentUserRole = me?.type ?: _state.value.currentUserRole,
                        currentUserPermissions = me?.permissions ?: _state.value.currentUserPermissions,
                    )
                    // the connection just came back: hand over anything listened to without it
                    if (ok && wasOffline) {
                        pushLocalProgress(progress)
                        runCatching { api.syncListeningSessions() }
                    }
                }
            }
        }
    }

    fun login(server: String, username: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val user = api.login(server.trim(), username.trim(), password)
                _state.value = _state.value.copy(
                    loggedIn = true,
                    loading = false,
                    serverOnline = true,
                    serverChecked = true,
                    currentUserRole = user.type,
                    currentUserPermissions = user.permissions,
                )
                refresh()
                onDone(true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Login failed")
                onDone(false)
            }
        }
    }

    /** Switch the catalogue shown in the Library tab, retaining that choice for next launch. */
    fun selectLibrary(libraryId: String) {
        if (libraryId == _state.value.activeLibraryId && !_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(activeLibraryId = libraryId, loading = true, error = null)
            store.setActiveLibraryId(libraryId)
            refresh(libraryId)
        }
    }

    fun refresh(requestedLibraryId: String? = null) {
        val loadGeneration = ++libraryLoadGeneration
        viewModelScope.launch {
            // Details go stale too - a book can gain or lose files on the server - so a
            // refresh drops them; prefetchContinue re-warms the top of the shelf after.
            shelf.itemCache.clear()
            // disk work, off the main thread — see the note in init
            val (downloaded, localItems) = withContext(Dispatchers.IO) {
                downloads.downloadedIds().toSet() to local.items()
            }
            val hasServer = store.token() != null && store.serverUrl() != null

            // every book that can be played without the server, for the offline paths below.
            // Cached entries come first so their (fresher) server metadata wins over the
            // snapshot saved at download time.
            suspend fun offlineItems() = withContext(Dispatchers.IO) {
                (_state.value.allItems + downloaded.mapNotNull { downloads.localItem(it) } + localItems).unique()
            }

            // no server configured: nothing to wait for
            if (!hasServer) {
                if (loadGeneration != libraryLoadGeneration) return@launch
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false, serverChecked = true,
                    allItems = offlineItems(), downloadedIds = downloaded, localCount = localItems.size,
                    currentUserRole = null, currentUserPermissions = null
                )
                return@launch
            }

            _state.value = _state.value.copy(loading = true, error = null)

            // quick reachability probe (2s) so an absent server costs 2s, not four timeouts
            if (!api.reachable()) {
                if (loadGeneration != libraryLoadGeneration) return@launch
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false, serverChecked = true,
                    allItems = offlineItems(),
                    downloadedIds = downloaded, localCount = localItems.size
                )
                return@launch
            }

            try {
                val libraries = api.libraries()
                if (loadGeneration != libraryLoadGeneration) return@launch
                val savedLibraryId = requestedLibraryId ?: store.activeLibraryId()
                // A library may have been deleted or its access revoked since it was last
                // selected. In that case use the first accessible one and repair the choice.
                val lib = libraries.firstOrNull { it.id == savedLibraryId } ?: libraries.firstOrNull()
                if (lib != null && lib.id != savedLibraryId) store.setActiveLibraryId(lib.id)
                val items = if (lib != null) api.libraryItems(lib.id) else emptyList()
                val series = if (lib != null) try { api.librarySeries(lib.id) } catch (_: Exception) { emptyList() } else emptyList()
                val authors = if (lib != null) try { api.libraryAuthors(lib.id) } catch (_: Exception) { emptyList() } else emptyList()
                val me = api.me()
                val progress = me.mediaProgress.associateBy { it.libraryItemId }
                if (loadGeneration != libraryLoadGeneration) return@launch
                cache.write(
                    CachedLibrary(
                        items = items,
                        series = series,
                        authors = authors,
                        progress = progress.values.toList(),
                        libraryId = lib?.id.orEmpty(),
                    )
                )
                _state.value = _state.value.copy(
                    allItems = (items + localItems).unique(), series = series, authors = authors,
                    libraries = libraries, activeLibraryId = lib?.id, serverProgress = progress,
                    loading = false, serverOnline = true, serverChecked = true, downloadedIds = downloaded,
                    localCount = localItems.size,
                    currentUserRole = me.type,
                    currentUserPermissions = me.permissions,
                    coverRevision = _state.value.coverRevision + 1,
                )
                prefetchContinue()
                pushLocalProgress(progress)
                runCatching { api.syncListeningSessions() }
                scheduleAutoMatchNewBooks(items, me.type)
            } catch (e: Exception) {
                if (loadGeneration != libraryLoadGeneration) return@launch
                // keep whatever is already on screen (cache + local + downloads)
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false, serverChecked = true,
                    allItems = offlineItems(),
                    downloadedIds = downloaded, localCount = localItems.size,
                    error = null
                )
            }
        }
    }

    /**
     * Enabling starts with the currently visible library as the baseline. Consequently
     * only genuinely new server items are changed, never a user's existing collection.
     */
    fun setAutoMatchNewBooks(enabled: Boolean) = viewModelScope.launch {
        if (enabled) {
            store.setAutoMatchKnownItems(
                _state.value.allItems.filterNot { LocalLibrary.isLocal(it.id) }.map { it.id }
            )
        }
        store.setAutoMatchNewBooks(enabled)
        if (!enabled) {
            _state.value = _state.value.copy(metadataMatchReviews = emptyList())
        }
    }

    private suspend fun reviewProvider(library: AbsLibrary?): String =
        if (store.metadataMatchProviderFlow.first() == "library") {
            library?.provider?.takeIf(String::isNotBlank) ?: "audible.uk"
        } else "audible.uk"

    private fun scheduleAutoMatchNewBooks(items: List<LibraryItem>, userRole: String?) {
        if (userRole != "root" && userRole != "admin") return
        if (_admin.value.runningAction?.startsWith("match-") == true) return
        if (autoMatchJob?.isActive == true) return
        autoMatchJob = viewModelScope.launch {
            if (!store.autoMatchNewBooksFlow.first()) return@launch
            val known = store.autoMatchKnownItems()
            // Migration/first enable safety: establish a baseline instead of matching an
            // existing library that the user never asked Fukuro to change.
            if (known.isEmpty()) {
                store.setAutoMatchKnownItems(items.map { it.id })
                return@launch
            }
            val alreadyQueued = _state.value.metadataMatchReviews.map { it.item.id }.toSet()
            val added = items.filterNot { it.id in known || it.id in alreadyQueued }
            if (added.isEmpty()) return@launch
            val needsMatch = added.filter { item ->
                val metadata = item.media.metadata
                metadata.authorName.isNullOrBlank() || metadata.genres.isEmpty() || item.tags.isEmpty() ||
                    metadata.description.isNullOrBlank() ||
                    (metadata.isbn.isNullOrBlank() && metadata.asin.isNullOrBlank())
            }
            store.addAutoMatchKnownItems(added.filterNot { it in needsMatch }.map { it.id })
            if (needsMatch.isEmpty()) return@launch
            val libraries = _state.value.libraries.associateBy { it.id }
            for (item in needsMatch) {
                try {
                    val provider = reviewProvider(libraries[item.libraryId])
                    val suggestion = api.bookMatchCandidates(item, provider)
                        .firstOrNull { it.title.isNotBlank() }
                    val review = suggestion?.let { MetadataMatchReview(item, provider, it) }
                    if (review == null || proposedMatchFields(review).isEmpty()) {
                        store.addAutoMatchKnownItems(listOf(item.id))
                        _admin.value = _admin.value.copy(
                            message = "No usable metadata changes found for ${item.media.metadata.title ?: item.relPath}",
                            success = false,
                        )
                    } else {
                        if (_state.value.metadataMatchReviews.none { it.item.id == item.id }) {
                            _state.value = _state.value.copy(
                                metadataMatchReviews = _state.value.metadataMatchReviews + review
                            )
                        }
                    }
                } catch (e: Exception) {
                    _admin.value = _admin.value.copy(
                        message = "Metadata preview failed: ${e.message ?: "unknown error"}",
                        success = false,
                    )
                    // Failed IDs stay unknown, so a later library refresh can retry them.
                }
            }
        }
    }

    fun acceptMetadataMatch(review: MetadataMatchReview, selected: Set<MatchField>) = viewModelScope.launch {
        if (_state.value.metadataMatchApplying) return@launch
        _state.value = _state.value.copy(metadataMatchApplying = true, metadataMatchError = null)
        try {
            api.applyBookMatch(review, selected)
            store.addAutoMatchKnownItems(listOf(review.item.id))
            _state.value = _state.value.copy(
                metadataMatchReviews = _state.value.metadataMatchReviews.filterNot {
                    it.item.id == review.item.id
                },
                metadataMatchApplying = false,
                metadataMatchError = null,
            )
            _admin.value = _admin.value.copy(message = "Metadata accepted", success = true)
            refresh()
            if (review.replaceExisting) manualReviewGate?.complete(true)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                metadataMatchApplying = false,
                metadataMatchError = e.message?.take(200) ?: "Could not apply metadata",
            )
            _admin.value = _admin.value.copy(
                message = "Could not apply metadata: ${e.message ?: "unknown error"}",
                success = false,
            )
        }
    }

    fun declineMetadataMatch(review: MetadataMatchReview) = viewModelScope.launch {
        store.addAutoMatchKnownItems(listOf(review.item.id))
        _state.value = _state.value.copy(
            metadataMatchReviews = _state.value.metadataMatchReviews.filterNot {
                it.item.id == review.item.id
            },
            metadataMatchError = null,
        )
        _admin.value = _admin.value.copy(message = "Metadata match declined", success = true)
        if (review.replaceExisting) manualReviewGate?.complete(true)
    }

    fun stopMetadataMatching(review: MetadataMatchReview) {
        if (!review.replaceExisting || _state.value.metadataMatchApplying) return
        _state.value = _state.value.copy(
            metadataMatchReviews = _state.value.metadataMatchReviews.filterNot { it.item.id == review.item.id },
            metadataMatchError = null,
        )
        manualReviewGate?.complete(false)
    }

    /**
     * Hands the server every position it hasn't heard yet — what was listened to while it
     * was unreachable. Called whenever a refresh or a ping finds it back.
     *
     * Failures are ignored on purpose: the local record stays as it is, so the next
     * reconnect tries again. Nothing is ever deleted here, which is what makes it safe to
     * run on every reconnect.
     */
    private suspend fun pushLocalProgress(server: Map<String, MediaProgress>) {
        val local = store.localProgress()
        for ((itemId, own) in local) {
            if (LocalLibrary.isLocal(itemId)) continue // on-device books have no server side
            if (own.updatedAt <= 0L) continue // migrated from before positions were timestamped
            val theirs = server[itemId]
            if (theirs != null && theirs.lastUpdate >= own.updatedAt) continue
            val duration = _state.value.allItems.firstOrNull { it.id == itemId }?.media?.duration
                ?: theirs?.duration ?: 0.0
            try {
                api.updateProgress(itemId, own.pos, duration)
                // A manual bookmark can reopen a book while offline. Its later position
                // upload must also clear the server's completed flag, otherwise the server
                // could hide it despite the newer local resume point.
                if (theirs?.isFinished == true && !own.finished) {
                    api.markFinished(itemId, false)
                }
                store.recordProgressEvent(itemId, own.pos, "Reconnected sync completed")
            } catch (_: Exception) {
                store.recordProgressEvent(itemId, own.pos, "Reconnected sync failed")
            }
        }
    }

    /** Lets the user in without a server; they can still sign in later from Settings. */
    fun continueOffline(onDone: () -> Unit) = viewModelScope.launch {
        store.setOfflineOnly(true)
        _state.value = _state.value.copy(offlineOnly = true)
        refresh()
        onDone()
    }

    /** Re-reads the on-device folder the user picked in Settings. */
    fun rescanLocal() = viewModelScope.launch {
        _state.value = _state.value.copy(scanning = true)
        val found = local.rescan() // already runs on IO
        val serverItems = _state.value.allItems.filterNot { LocalLibrary.isLocal(it.id) }
        _state.value = _state.value.copy(
            scanning = false,
            allItems = (serverItems + found).unique(),
            localCount = found.size,
        )
    }

    fun setLocalFolder(uri: String) = viewModelScope.launch {
        store.setLocalFolder(uri)
        rescanLocal()
    }

    fun download(itemId: String) = viewModelScope.launch {
        downloads.download(itemId)
        _state.value = _state.value.copy(downloadedIds = downloads.downloadedIds().toSet())
    }

    fun deleteDownload(itemId: String) = viewModelScope.launch {
        downloads.delete(itemId)
        _state.value = _state.value.copy(downloadedIds = downloads.downloadedIds().toSet())
    }

    /** Download every book in a series that isn't already on the device, one after another. */
    fun downloadAll(itemIds: List<String>) = viewModelScope.launch {
        itemIds.filterNot { downloads.isDownloaded(it) }.forEach { id ->
            downloads.download(id)
            _state.value = _state.value.copy(downloadedIds = downloads.downloadedIds().toSet())
        }
    }

    fun deleteAll(itemIds: List<String>) = viewModelScope.launch {
        itemIds.forEach { downloads.delete(it) }
        _state.value = _state.value.copy(downloadedIds = downloads.downloadedIds().toSet())
    }

    private suspend fun removeDownloadIfCompleted(itemId: String, finished: Boolean) {
        if (!finished) return
        if (!store.autoRemoveCompletedDownloadsFlow.first()) return
        if (!downloads.isDownloaded(itemId)) return
        downloads.delete(itemId)
        _state.value = _state.value.copy(downloadedIds = downloads.downloadedIds().toSet())
    }

    /** Marked finished on the device first, so it holds with no server and syncs after. */
    fun markFinished(itemId: String, finished: Boolean) = viewModelScope.launch {
        val duration = _state.value.allItems.firstOrNull { it.id == itemId }?.media?.duration ?: 0.0
        store.setLocalProgress(
            itemId, if (finished) duration else 0.0, finished = finished,
            source = if (finished) "Marked finished" else "Marked unfinished",
        )
        try { api.markFinished(itemId, finished) } catch (_: Exception) {}
        removeDownloadIfCompleted(itemId, finished)
        refresh()
    }

    fun resetProgress(itemId: String) = viewModelScope.launch {
        // clear the device's record first: it is the one the screens read, and it is the
        // only one there is when the server is away
        store.setLocalProgress(itemId, 0.0, finished = false, source = "Reset progress")
        // look up the progress record's own id; the item id is not accepted
        val progressId = _state.value.serverProgress[itemId]?.id
            ?: try { api.me().mediaProgress.firstOrNull { it.libraryItemId == itemId }?.id } catch (_: Exception) { null }
        if (progressId.isNullOrBlank()) {
            refresh()
            return@launch
        }
        try {
            api.resetProgress(progressId)
            refresh()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Could not reset progress: ${e.message?.take(120)}")
        }
    }

    /**
     * Stores a bookmark without touching the playback service. This is used by a book page
     * that is not the one currently playing, so its scrubber can set where a later Play
     * should resume without interrupting the active book.
     */
    fun setManualProgress(itemId: String, positionSec: Double) = viewModelScope.launch {
        val duration = _state.value.allItems.firstOrNull { it.id == itemId }?.media?.duration
            ?: _state.value.serverProgress[itemId]?.duration
            ?: 0.0
        val position = if (duration > 0.0) positionSec.coerceIn(0.0, duration) else positionSec.coerceAtLeast(0.0)

        // Setting a place in a book means it is resumable, including if it was previously
        // marked finished. The local record updates the UI immediately and survives offline.
        store.setLocalProgress(itemId, position, finished = false, source = "Manual position")
        if (LocalLibrary.isLocal(itemId)) return@launch

        try {
            api.updateProgress(itemId, position, duration)
            api.markFinished(itemId, false)
        } catch (_: Exception) {
            // The next connected refresh pushes the durable local position.
        }
    }

    fun toggleFavorite(itemId: String) = viewModelScope.launch { store.toggleFavorite(itemId) }

    /** A series heart represents every book in it and updates them atomically. */
    fun toggleSeriesFavorite(itemIds: List<String>) = viewModelScope.launch {
        val ids = itemIds.filter { it.isNotBlank() }.distinct()
        val makeFavorite = ids.any { it !in _state.value.favorites }
        store.setFavorites(ids, makeFavorite)
    }

    /* ---------------- home screen shortcuts ---------------- */

    /** Pins a book to the launcher. The cover is baked into the icon at pin time. */
    fun pinBookShortcut(itemId: String) = viewModelScope.launch {
        val item = _state.value.items.firstOrNull { it.id == itemId } ?: return@launch
        Shortcuts.pinBook(getApplication(), item, coverModel(itemId), api.http)
    }

    fun pinSeriesShortcut(seriesId: String) = viewModelScope.launch {
        val series = _state.value.series.firstOrNull { it.id == seriesId } ?: return@launch
        val cover = series.books.firstOrNull()?.let { coverModel(it.id) }
        Shortcuts.pinSeries(getApplication(), series, cover, api.http)
    }

    /* ---------------- app updates ---------------- */

    /**
     * Looks for a newer release. The automatic call (app start) respects the setting and
     * only goes out once every six hours; [manual] ignores both and always reports back.
     */
    fun checkForUpdate(manual: Boolean = false) = viewModelScope.launch {
        val u = _update.value
        if (u.checking || u.downloading) return@launch
        if (!manual) {
            if (!store.autoUpdateFlow.first()) return@launch
            if (System.currentTimeMillis() - store.lastUpdateCheck() < 6 * 60 * 60 * 1000L) return@launch
            // nothing about this is urgent, and landing a banner mid-launch animation
            // costs a frame for no reason
            delay(2_500)
        }
        _update.value = u.copy(checking = true, error = null, upToDate = false)
        try {
            val info = shelf.updater.check()
            store.setLastUpdateCheck(System.currentTimeMillis())
            _update.value = _update.value.copy(
                checking = false, info = info, upToDate = info == null, dismissed = false
            )
        } catch (e: Exception) {
            // an unreachable GitHub is not worth shouting about on a background check
            _update.value = _update.value.copy(
                checking = false,
                error = if (manual) (e.message ?: "Could not reach GitHub") else null
            )
        }
    }

    fun downloadUpdate() = viewModelScope.launch {
        val info = _update.value.info ?: return@launch
        if (_update.value.downloading) return@launch
        _update.value = _update.value.copy(downloading = true, progress = 0f, error = null)
        try {
            // the download reports every chunk; only redraw when the percentage moves
            var lastPct = -1
            val file = shelf.updater.download(info) { p ->
                val pct = (p * 100).toInt()
                if (pct != lastPct) {
                    lastPct = pct
                    _update.value = _update.value.copy(progress = p)
                }
            }
            _update.value = _update.value.copy(downloading = false, file = file)
            installUpdate() // straight into the installer; nothing else to wait for
        } catch (e: Exception) {
            _update.value = _update.value.copy(
                downloading = false, error = e.message ?: "Download failed"
            )
        }
    }

    /**
     * Hands the downloaded APK to the system installer. If the user has not allowed this
     * app to install, that has to happen first — the file stays put meanwhile, so coming
     * back is one tap.
     */
    fun installUpdate() {
        val file = _update.value.file ?: return
        if (!shelf.updater.canInstall()) {
            _update.value = _update.value.copy(needsPermission = true)
            return
        }
        _update.value = _update.value.copy(needsPermission = false)
        shelf.updater.install(file)
    }

    fun grantInstallPermission() = shelf.updater.requestInstallPermission()

    /** When the check itself cannot get through, the browser still can. */
    fun openReleasesPage() = shelf.updater.openReleasesPage()

    fun dismissUpdate() { _update.value = _update.value.copy(dismissed = true) }

    /**
     * The neighbouring book in the same series, or null when there isn't one - which is
     * what makes a swipe fall back to chapters for a standalone book.
     */
    fun siblingInSeries(itemId: String, forward: Boolean): String? {
        val items = _state.value.items
        val here = items.firstOrNull { it.id == itemId } ?: return null
        val raw = here.media.metadata.seriesName?.takeIf { it.isNotBlank() } ?: return null
        val name = raw.substringBeforeLast('#').trim()
        val seq = raw.substringAfterLast('#').trim().toDoubleOrNull() ?: return null
        val siblings = items.mapNotNull { other ->
            val s = other.media.metadata.seriesName ?: return@mapNotNull null
            if (s.substringBeforeLast('#').trim() != name) return@mapNotNull null
            val n = s.substringAfterLast('#').trim().toDoubleOrNull() ?: return@mapNotNull null
            n to other.id
        }
        return if (forward) siblings.filter { it.first > seq }.minByOrNull { it.first }?.second
        else siblings.filter { it.first < seq }.maxByOrNull { it.first }?.second
    }

    fun hideFromContinue(itemId: String) = viewModelScope.launch { store.hideFromContinue(itemId) }
    fun unhideFromContinue(itemId: String) = viewModelScope.launch { store.unhideFromContinue(itemId) }

    /** Books on the Continue Listening shelf, most recently listened first. */
    fun continueListening(): List<LibraryItem> =
        _state.value.let { s ->
            s.items.filter { item ->
                val p = s.progress[item.id]
                p != null && !p.isFinished && p.progress > 0.001 && item.id !in s.continueHidden
            }.sortedByDescending { s.progress[it.id]?.lastUpdate ?: 0L }
        }

    /**
     * Fetches details for the first few Continue Listening books and opens a
     * connection to the top one's audio, so pressing play doesn't start with a
     * round trip to the server.
     */
    private fun prefetchContinue() = viewModelScope.launch {
        if (!_state.value.serverOnline) return@launch
        val queue = continueListening().take(4)
        queue.forEachIndexed { index, item ->
            if (LocalLibrary.isLocal(item.id) || downloads.isDownloaded(item.id)) return@forEachIndexed
            runCatching {
                val full = shelf.itemCache[item.id]?.takeIf { it.media.audioFiles.isNotEmpty() }
                    ?: api.item(item.id).also {
                        if (it.media.audioFiles.isNotEmpty()) shelf.itemCache[item.id] = it
                    }
                // only warm the audio connection for the book most likely to be played
                if (index == 0) full.media.audioFiles.firstOrNull()?.let { api.warmUp(item.id, it.ino) }
            }
        }
    }

    /**
     * Removes the metadata round trip from the Play tap where possible. This does not
     * download the book; it fetches the playlist shape and opens the likely audio URL.
     */
    fun prefetchBook(itemId: String) = viewModelScope.launch {
        if (LocalLibrary.isLocal(itemId) || downloads.isDownloaded(itemId) || !_state.value.serverOnline) return@launch
        runCatching {
            val full = shelf.itemCache[itemId]?.takeIf { it.media.audioFiles.isNotEmpty() }
                ?: api.item(itemId).also {
                    if (it.media.audioFiles.isNotEmpty()) shelf.itemCache[itemId] = it
                }
            val pos = _state.value.progress[itemId]?.takeIf { !it.isFinished }?.currentTime ?: 0.0
            val target = audioFileAt(full, pos) ?: full.media.audioFiles.sortedBy { it.index }.firstOrNull()
            target?.let { api.warmUp(itemId, it.ino) }
        }
    }

    fun cachePlayableItem(item: LibraryItem) {
        if (item.media.audioFiles.isNotEmpty()) shelf.itemCache[item.id] = item
    }

    private fun audioFileAt(item: LibraryItem, positionSec: Double): AudioFile? {
        var start = 0.0
        return item.media.audioFiles.sortedBy { it.index }.firstOrNull { file ->
            val end = start + file.duration
            val hit = positionSec >= start && positionSec < end
            start = end
            hit
        }
    }

    fun renameBook(itemId: String, newTitle: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        try {
            api.renameItem(itemId, newTitle.trim())
            refresh()
            onDone(null)
        } catch (e: Exception) { onDone(e.message ?: "Rename failed") }
    }

    fun editCover(itemId: String, uri: Uri, onDone: (String?) -> Unit) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { coverOverrides.setCover(itemId, uri) }
            _state.value = _state.value.copy(coverRevision = _state.value.coverRevision + 1)
            onDone(null)
        } catch (e: Exception) { onDone(e.message ?: "Could not save cover") }
    }

    fun resetCover(itemId: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) { coverOverrides.clearCover(itemId) }
        _state.value = _state.value.copy(coverRevision = _state.value.coverRevision + 1)
    }

    private val _upload = MutableStateFlow(UploadUi())
    val upload: StateFlow<UploadUi> = _upload

    private fun isAdmin() = _state.value.canOpenAdminSettings

    private fun adminOnlyMessage() = "Admin account required"

    private fun runAdminAction(action: String, block: suspend () -> String) = viewModelScope.launch {
        if (!isAdmin()) {
            _admin.value = _admin.value.copy(message = adminOnlyMessage(), success = false)
            return@launch
        }
        _admin.value = _admin.value.copy(runningAction = action, message = null, success = false)
        try {
            val message = block()
            _admin.value = _admin.value.copy(runningAction = null, message = message, success = true)
        } catch (e: Exception) {
            _admin.value = _admin.value.copy(
                runningAction = null,
                message = e.message?.take(200) ?: "Admin action failed",
                success = false,
            )
        }
    }

    fun scanLibrary(libraryId: String, force: Boolean = false) = runAdminAction(
        if (force) "force-scan-$libraryId" else "scan-$libraryId"
    ) {
        api.scanLibrary(libraryId, force)
        _admin.value = _admin.value.copy(
            message = if (force) "Force rescan running on Audiobookshelf…" else "Library scan running on Audiobookshelf…",
            success = true,
        )
        // The scan endpoint acknowledges the request before the server task completes.
        // Keep the button in its running state until ABS removes that task.
        var observed = false
        repeat(900) { poll ->
            val active = api.activeTasks().any {
                it.action == "library-scan" && it.data.libraryId == libraryId
            }
            if (active) observed = true
            if (!active && (observed || poll >= 2)) {
                refresh()
                return@runAdminAction if (force) "Force rescan finished" else "Library scan finished"
            }
            delay(2_000)
        }
        throw IllegalStateException("Scan is still running after 30 minutes. Check Audiobookshelf tasks.")
    }

    fun matchLibrary(libraryId: String) = runAdminAction("match-$libraryId") {
        val library = _state.value.libraries.firstOrNull { it.id == libraryId }
            ?: throw IllegalStateException("Library not loaded")
        require(library.mediaType == "book") { "Metadata matching is only available for book libraries" }
        check(api.activeTasks().none { it.action == "library-scan" && it.data.libraryId == libraryId }) {
            "Wait for the Audiobookshelf library scan to finish before matching metadata"
        }
        val provider = reviewProvider(library)
        val books = api.allLibraryItems(libraryId)
        var reviewed = 0
        var skipped = 0
        try {
            books.forEachIndexed { index, book ->
                _admin.value = _admin.value.copy(
                    message = "Checking metadata ${index + 1}/${books.size}: ${book.media.metadata.title ?: book.relPath}",
                    success = true,
                )
                val suggestion = api.bookMatchCandidates(book, provider)
                    .firstOrNull { it.title.isNotBlank() }
                if (suggestion == null || proposedMatchFields(
                        MetadataMatchReview(book, provider, suggestion, replaceExisting = true)
                    ).isEmpty()) {
                    skipped++
                    return@forEachIndexed
                }
                val review = MetadataMatchReview(book, provider, suggestion, replaceExisting = true)
                manualReviewGate = CompletableDeferred()
                _state.value = _state.value.copy(
                    metadataMatchError = null,
                    metadataMatchReviews = listOf(review) + _state.value.metadataMatchReviews.filterNot {
                        it.item.id == book.id
                    },
                )
                _admin.value = _admin.value.copy(
                    message = "Review ${index + 1}/${books.size}: ${book.media.metadata.title ?: book.relPath}",
                    success = true,
                )
                if (manualReviewGate?.await() == false) {
                    return@runAdminAction "Metadata review stopped after $reviewed book(s)"
                }
                reviewed++
                manualReviewGate = null
            }
        } finally {
            manualReviewGate = null
        }
        "Metadata review finished: $reviewed reviewed, $skipped unchanged or unmatched"
    }

    fun loadAdminUsers() = runAdminAction("users") {
        val users = api.users()
        val online = api.onlineUsers()
        _admin.value = _admin.value.copy(
            users = users,
            onlineUsers = online.usersOnline,
            openSessions = online.openSessions,
        )
        "Loaded ${users.size} user(s)"
    }

    fun uploadBook(title: String, author: String, series: String, uris: List<Uri>) = viewModelScope.launch {
        if (!isAdmin()) {
            _upload.value = UploadUi(message = adminOnlyMessage())
            return@launch
        }
        if (title.isBlank() || uris.isEmpty()) {
            _upload.value = UploadUi(message = "Pick at least one file and enter a title"); return@launch
        }
        _upload.value = UploadUi(running = true, message = "Uploading ${uris.size} file(s)…")
        try {
            val resolver = getApplication<Application>().contentResolver
            val files = uris.mapIndexed { i, uri ->
                var name = "file_$i.m4b"; var size = -1L
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (c.moveToFirst()) {
                        if (ni >= 0) name = c.getString(ni) ?: name
                        if (si >= 0) size = c.getLong(si)
                    }
                }
                AbsApi.UploadFile(
                    name = name, size = size,
                    mime = resolver.getType(uri) ?: "audio/mpeg",
                    open = { resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot read $name") }
                )
            }
            api.uploadBook(title.trim(), author.trim().ifBlank { null }, series.trim().ifBlank { null }, files)
            _upload.value = UploadUi(success = true, message = "Uploaded! The server is scanning it now.")
            refresh()
        } catch (e: Exception) {
            _upload.value = UploadUi(message = "Upload failed: ${e.message?.take(200)}")
        }
    }

    fun resetUpload() { _upload.value = UploadUi() }

    fun logout() = viewModelScope.launch {
        store.logout()
        _state.value = UiState(loggedIn = false, serverChecked = true)
    }
}
