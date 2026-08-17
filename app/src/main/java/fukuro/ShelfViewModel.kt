package fukuro

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.net.Uri

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
    val serverProgress: Map<String, MediaProgress> = emptyMap(),
    val localProgress: Map<String, LocalProgress> = emptyMap(),
    val downloadedIds: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val continueHidden: Set<String> = emptySet(),
    val progressStyle: String = "circle", // "circle" | "bar"
    val coverSize: Int = 2, // 0..4, 2 = default
) {
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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _update = MutableStateFlow(UpdateUi())
    val update: StateFlow<UpdateUi> = _update

    /** Lazy lists key on item id, and a repeat key is a hard crash — never allow one. */
    private fun List<LibraryItem>.unique() = distinctBy { it.id }

    val local get() = shelf.local
    private val cache get() = shelf.cache

    /**
     * Cover for any book. On-device books use the file scanned out of their folder and
     * downloaded books the cover saved next to their audio — the server URL is only the
     * last resort, so a downloaded book still shows its art with no connection.
     */
    fun coverModel(itemId: String): Any? = when {
        LocalLibrary.isLocal(itemId) -> local.coverFile(itemId)
        // covers are resolved while composing, so only touch the disk for books that
        // are actually on it
        itemId in _state.value.downloadedIds -> downloads.localCover(itemId) ?: api.coverUrl(itemId)
        else -> api.coverUrl(itemId)
    }

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
                    val progress = if (ok) try {
                        api.me().mediaProgress.associateBy { it.libraryItemId }
                    } catch (_: Exception) { _state.value.serverProgress } else _state.value.serverProgress
                    _state.value = _state.value.copy(
                        serverOnline = ok, serverChecked = true, serverProgress = progress
                    )
                    // the connection just came back: hand over anything listened to without it
                    if (ok && wasOffline) pushLocalProgress(progress)
                }
            }
        }
    }

    fun login(server: String, username: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                api.login(server.trim(), username.trim(), password)
                _state.value = _state.value.copy(
                    loggedIn = true, loading = false, serverOnline = true, serverChecked = true
                )
                refresh()
                onDone(true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Login failed")
                onDone(false)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
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
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false, serverChecked = true,
                    allItems = offlineItems(), downloadedIds = downloaded, localCount = localItems.size
                )
                return@launch
            }

            _state.value = _state.value.copy(loading = true, error = null)

            // quick reachability probe (2s) so an absent server costs 2s, not four timeouts
            if (!api.reachable()) {
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false, serverChecked = true,
                    allItems = offlineItems(),
                    downloadedIds = downloaded, localCount = localItems.size
                )
                return@launch
            }

            try {
                val lib = api.libraries().firstOrNull()
                val items = if (lib != null) api.libraryItems(lib.id) else emptyList()
                val series = if (lib != null) try { api.librarySeries(lib.id) } catch (_: Exception) { emptyList() } else emptyList()
                val authors = if (lib != null) try { api.libraryAuthors(lib.id) } catch (_: Exception) { emptyList() } else emptyList()
                val progress = api.me().mediaProgress.associateBy { it.libraryItemId }
                cache.write(CachedLibrary(items, series, authors, progress.values.toList()))
                _state.value = _state.value.copy(
                    allItems = (items + localItems).unique(), series = series, authors = authors,
                    serverProgress = progress,
                    loading = false, serverOnline = true, serverChecked = true, downloadedIds = downloaded,
                    localCount = localItems.size
                )
                prefetchContinue()
                pushLocalProgress(progress)
            } catch (e: Exception) {
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
            runCatching { api.updateProgress(itemId, own.pos, duration) }
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

    /** Marked finished on the device first, so it holds with no server and syncs after. */
    fun markFinished(itemId: String, finished: Boolean) = viewModelScope.launch {
        val duration = _state.value.allItems.firstOrNull { it.id == itemId }?.media?.duration ?: 0.0
        store.setLocalProgress(itemId, if (finished) duration else 0.0, finished = finished)
        try { api.markFinished(itemId, finished) } catch (_: Exception) {}
        refresh()
    }

    fun resetProgress(itemId: String) = viewModelScope.launch {
        // clear the device's record first: it is the one the screens read, and it is the
        // only one there is when the server is away
        store.setLocalProgress(itemId, 0.0, finished = false)
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

    fun toggleFavorite(itemId: String) = viewModelScope.launch { store.toggleFavorite(itemId) }

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

    fun dismissUpdate() { _update.value = _update.value.copy(dismissed = true) }

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
                val full = shelf.itemCache[item.id] ?: api.item(item.id).also { shelf.itemCache[item.id] = it }
                // only warm the audio connection for the book most likely to be played
                if (index == 0) full.media.audioFiles.firstOrNull()?.let { api.warmUp(item.id, it.ino) }
            }
        }
    }

    fun renameBook(itemId: String, newTitle: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        try {
            api.renameItem(itemId, newTitle.trim())
            refresh()
            onDone(null)
        } catch (e: Exception) { onDone(e.message ?: "Rename failed") }
    }

    private val _upload = MutableStateFlow(UploadUi())
    val upload: StateFlow<UploadUi> = _upload

    fun uploadBook(title: String, author: String, series: String, uris: List<Uri>) = viewModelScope.launch {
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
        _state.value = UiState(loggedIn = false)
    }
}
