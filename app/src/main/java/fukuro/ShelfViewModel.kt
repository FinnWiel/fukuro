package fukuro

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
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
    val items: List<LibraryItem> = emptyList(),
    val series: List<AbsSeries> = emptyList(),
    val authors: List<AbsAuthor> = emptyList(),
    val progress: Map<String, MediaProgress> = emptyMap(),
    val downloadedIds: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val progressStyle: String = "circle", // "circle" | "bar"
    val coverSize: Int = 2, // 0..4, 2 = default
)

/** Upload page state. */
data class UploadUi(val running: Boolean = false, val message: String? = null, val success: Boolean = false)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {
    private val shelf = ShelfApp.from(app)
    val api get() = shelf.api
    val store get() = shelf.store
    val downloads get() = shelf.downloads
    val downloadStates: kotlinx.coroutines.flow.StateFlow<Map<String, DownloadState>> get() = shelf.downloads.states

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Lazy lists key on item id, and a repeat key is a hard crash — never allow one. */
    private fun List<LibraryItem>.unique() = distinctBy { it.id }

    val local get() = shelf.local
    private val cache get() = shelf.cache

    /** Cover for any book: a local file for on-device books, the server URL otherwise. */
    fun coverModel(itemId: String): Any? =
        if (LocalLibrary.isLocal(itemId)) local.coverFile(itemId) else api.coverUrl(itemId)

    init {
        viewModelScope.launch {
            val hasToken = store.token() != null && store.serverUrl() != null
            val offline = store.offlineOnlyFlow.first()

            // 1) paint immediately from disk: last server response + on-device books.
            //    No network on this path, so a dead server costs nothing.
            val cached = cache.read()
            val localItems = local.items()
            _state.value = _state.value.copy(
                loggedIn = hasToken,
                offlineOnly = offline,
                items = ((cached?.items ?: emptyList()) + localItems).unique(),
                series = cached?.series ?: emptyList(),
                authors = cached?.authors ?: emptyList(),
                progress = (cached?.progress ?: emptyList()).associateBy { it.libraryItemId },
                downloadedIds = downloads.downloadedIds().toSet(),
                localCount = localItems.size,
            )

            // 2) then talk to the server, if there is one
            if (hasToken) refresh()
        }
        // keep favorites in sync with the persisted set
        viewModelScope.launch {
            store.favoritesFlow.collect { fav -> _state.value = _state.value.copy(favorites = fav) }
        }
        viewModelScope.launch {
            store.progressStyleFlow.collect { s -> _state.value = _state.value.copy(progressStyle = s) }
        }
        viewModelScope.launch {
            store.coverSizeFlow.collect { s -> _state.value = _state.value.copy(coverSize = s) }
        }
        // connection indicator + live progress: every 15s
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                if (_state.value.loggedIn) {
                    val ok = api.ping()
                    // refresh just the progress map so list progress bars track live playback
                    val progress = if (ok) try {
                        api.me().mediaProgress.associateBy { it.libraryItemId }
                    } catch (_: Exception) { _state.value.progress } else _state.value.progress
                    _state.value = _state.value.copy(serverOnline = ok, progress = progress)
                }
            }
        }
    }

    fun login(server: String, username: String, password: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                api.login(server.trim(), username.trim(), password)
                _state.value = _state.value.copy(loggedIn = true, loading = false, serverOnline = true)
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
            val downloaded = downloads.downloadedIds().toSet()
            val localItems = local.items()
            val hasServer = store.token() != null && store.serverUrl() != null

            // no server configured: nothing to wait for
            if (!hasServer) {
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false,
                    items = localItems.unique(), downloadedIds = downloaded, localCount = localItems.size
                )
                return@launch
            }

            _state.value = _state.value.copy(loading = true, error = null)

            // quick reachability probe (2s) so an absent server costs 2s, not four timeouts
            if (!api.reachable()) {
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false,
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
                    items = (items + localItems).unique(), series = series, authors = authors, progress = progress,
                    loading = false, serverOnline = true, downloadedIds = downloaded,
                    localCount = localItems.size
                )
            } catch (e: Exception) {
                // keep whatever is already on screen (cache + local + downloads)
                val fallback = downloaded.mapNotNull { downloads.localItem(it) }
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false,
                    items = if (_state.value.items.isNotEmpty()) _state.value.items else (fallback + localItems).unique(),
                    downloadedIds = downloaded, localCount = localItems.size,
                    error = null
                )
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
        val found = local.rescan()
        val serverItems = _state.value.items.filterNot { LocalLibrary.isLocal(it.id) }
        _state.value = _state.value.copy(
            scanning = false,
            items = (serverItems + found).unique(),
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

    fun markFinished(itemId: String, finished: Boolean) = viewModelScope.launch {
        try { api.markFinished(itemId, finished); refresh() } catch (_: Exception) {}
    }

    fun resetProgress(itemId: String) = viewModelScope.launch {
        // look up the progress record's own id; the item id is not accepted
        val progressId = _state.value.progress[itemId]?.id
            ?: try { api.me().mediaProgress.firstOrNull { it.libraryItemId == itemId }?.id } catch (_: Exception) { null }
        if (progressId.isNullOrBlank()) {
            // nothing stored server-side; still clear the local resume position
            store.setLocalProgress(itemId, 0.0)
            refresh()
            return@launch
        }
        try {
            api.resetProgress(progressId)
            store.setLocalProgress(itemId, 0.0)
            refresh()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Could not reset progress: ${e.message?.take(120)}")
        }
    }

    fun toggleFavorite(itemId: String) = viewModelScope.launch { store.toggleFavorite(itemId) }

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
