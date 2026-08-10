package nl.shazzoo.shelfplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.shazzoo.shelfplayer.ShelfApp
import nl.shazzoo.shelfplayer.data.DownloadState
import nl.shazzoo.shelfplayer.data.LibraryItem
import nl.shazzoo.shelfplayer.data.MediaProgress

data class UiState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val serverOnline: Boolean = false,
    val items: List<LibraryItem> = emptyList(),
    val progress: Map<String, MediaProgress> = emptyMap(),
    val downloadedIds: Set<String> = emptySet(),
)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {
    private val shelf = ShelfApp.from(app)
    val api get() = shelf.api
    val store get() = shelf.store
    val downloads get() = shelf.downloads
    val downloadStates: kotlinx.coroutines.flow.StateFlow<Map<String, DownloadState>> get() = shelf.downloads.states

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val hasToken = store.token() != null && store.serverUrl() != null
            _state.value = _state.value.copy(loggedIn = hasToken)
            if (hasToken) refresh()
        }
        // connection indicator: ping every 30s
        viewModelScope.launch {
            while (isActive) {
                if (_state.value.loggedIn) {
                    val ok = api.ping()
                    _state.value = _state.value.copy(serverOnline = ok)
                }
                delay(30_000)
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
            _state.value = _state.value.copy(loading = true, error = null)
            val downloaded = downloads.downloadedIds().toSet()
            try {
                val lib = api.libraries().firstOrNull()
                val items = if (lib != null) api.libraryItems(lib.id) else emptyList()
                val progress = api.me().mediaProgress.associateBy { it.libraryItemId }
                _state.value = _state.value.copy(
                    items = items, progress = progress, loading = false,
                    serverOnline = true, downloadedIds = downloaded
                )
            } catch (e: Exception) {
                // offline: fall back to downloaded books so the app stays usable
                val localItems = downloaded.mapNotNull { downloads.localItem(it) }
                _state.value = _state.value.copy(
                    loading = false, serverOnline = false,
                    items = if (localItems.isNotEmpty()) localItems else _state.value.items,
                    downloadedIds = downloaded,
                    error = if (localItems.isEmpty()) "Could not reach server: ${e.message?.take(120)}" else null
                )
            }
        }
    }

    fun download(itemId: String) = viewModelScope.launch {
        downloads.download(itemId)
        _state.value = _state.value.copy(downloadedIds = downloads.downloadedIds().toSet())
    }

    fun deleteDownload(itemId: String) = viewModelScope.launch {
        downloads.delete(itemId)
        _state.value = _state.value.copy(downloadedIds = downloads.downloadedIds().toSet())
    }

    fun markFinished(itemId: String, finished: Boolean) = viewModelScope.launch {
        try { api.markFinished(itemId, finished); refresh() } catch (_: Exception) {}
    }

    fun resetProgress(itemId: String) = viewModelScope.launch {
        try { api.resetProgress(itemId); refresh() } catch (_: Exception) {}
    }

    fun logout() = viewModelScope.launch {
        store.logout()
        _state.value = UiState(loggedIn = false)
    }
}
