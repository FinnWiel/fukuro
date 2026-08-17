package fukuro

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "fukuro")

/** Small persistent settings store. Blocking getters exist for non-suspend call sites (URL builders). */
class Store(private val context: Context) {

    /*
     * Values the UI and the player service need synchronously (cover URLs are built
     * while composing, on the main thread). Reading DataStore with runBlocking there
     * stalls the frame and can wedge the app, so keep a warm in-memory mirror instead.
     */
    @Volatile private var mServer: String? = null
    @Volatile private var mToken: String? = null
    @Volatile private var mLocalFolder: String = ""
    @Volatile private var mDownloadDir: String = ""
    @Volatile private var mSkipBack: Int = 10
    @Volatile private var mSkipForward: Int = 30
    @Volatile private var mTrackScope: String = "book"

    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        mirrorScope.launch {
            context.dataStore.data.collect { p ->
                mServer = p[K.SERVER]
                mToken = p[K.TOKEN]
                mLocalFolder = p[K.LOCAL_FOLDER] ?: ""
                mDownloadDir = p[K.DOWNLOAD_DIR] ?: ""
                mSkipBack = p[K.SKIP_BACK]?.toIntOrNull() ?: 10
                mSkipForward = p[K.SKIP_FORWARD]?.toIntOrNull() ?: 30
                mTrackScope = p[K.TRACK_SCOPE] ?: "book"
            }
        }
    }
    private object K {
        val SERVER = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val USERNAME = stringPreferencesKey("username")
        val THEME = stringPreferencesKey("theme") // system | dark | light
        val HOME_SECTIONS = stringPreferencesKey("home_sections") // csv order
        val LOCAL_PROGRESS = stringPreferencesKey("local_progress") // json {itemId: currentTimeSec}
        val ACCENT = stringPreferencesKey("accent") // "dynamic" or a key from ACCENT_COLORS
        val PROGRESS_STYLE = stringPreferencesKey("progress_style") // "circle" | "bar"
        val DOWNLOAD_DIR = stringPreferencesKey("download_dir") // absolute path, blank = app storage
        val COVER_SIZE = stringPreferencesKey("cover_size") // "0".."4"
        val FAVORITES_TOP = booleanPreferencesKey("favorites_top")
        val SKIP_BACK = stringPreferencesKey("skip_back")     // seconds
        val SKIP_FORWARD = stringPreferencesKey("skip_forward")
        val OFFLINE_ONLY = booleanPreferencesKey("offline_only")   // chose to use the app without a server
        val LOCAL_FOLDER = stringPreferencesKey("local_folder")    // SAF tree uri of the on-device library
        val TRACK_SCOPE = stringPreferencesKey("track_scope")      // "book" | "chapter"
        val CONTINUE_HIDDEN = stringPreferencesKey("continue_hidden") // csv of ids kept out of the shelf
        val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
        val UPDATE_LAST_CHECK = stringPreferencesKey("update_last_check") // epoch ms
        val API_KEY = stringPreferencesKey("abs_api_key")
        val SPEED = stringPreferencesKey("playback_speed")
        val LAST_ITEM = stringPreferencesKey("last_item") // what the system offers to resume
        val FAVORITES = stringPreferencesKey("favorites") // csv of item ids
    }

    val themeFlow: Flow<String> = context.dataStore.data.map { it[K.THEME] ?: "system" }
    val accentFlow: Flow<String> = context.dataStore.data.map { it[K.ACCENT] ?: DEFAULT_ACCENT }
    val progressStyleFlow: Flow<String> = context.dataStore.data.map { it[K.PROGRESS_STYLE] ?: "circle" }
    val downloadDirFlow: Flow<String> = context.dataStore.data.map { it[K.DOWNLOAD_DIR] ?: "" }
    val coverSizeFlow: Flow<Int> = context.dataStore.data.map { it[K.COVER_SIZE]?.toIntOrNull() ?: 2 }
    val favoritesTopFlow: Flow<Boolean> = context.dataStore.data.map { it[K.FAVORITES_TOP] ?: false }
    val skipBackFlow: Flow<Int> = context.dataStore.data.map { it[K.SKIP_BACK]?.toIntOrNull() ?: 10 }
    val skipForwardFlow: Flow<Int> = context.dataStore.data.map { it[K.SKIP_FORWARD]?.toIntOrNull() ?: 30 }
    val offlineOnlyFlow: Flow<Boolean> = context.dataStore.data.map { it[K.OFFLINE_ONLY] ?: false }
    val localFolderFlow: Flow<String> = context.dataStore.data.map { it[K.LOCAL_FOLDER] ?: "" }
    val trackScopeFlow: Flow<String> = context.dataStore.data.map { it[K.TRACK_SCOPE] ?: "book" }
    val autoUpdateFlow: Flow<Boolean> = context.dataStore.data.map { it[K.AUTO_UPDATE] ?: true }

    suspend fun setAutoUpdate(v: Boolean) = context.dataStore.edit { it[K.AUTO_UPDATE] = v }
    suspend fun lastUpdateCheck(): Long =
        context.dataStore.data.first()[K.UPDATE_LAST_CHECK]?.toLongOrNull() ?: 0L
    suspend fun setLastUpdateCheck(ms: Long) =
        context.dataStore.edit { it[K.UPDATE_LAST_CHECK] = ms.toString() }

    /** Books the user dismissed from Continue Listening; their progress is untouched. */
    val continueHiddenFlow: Flow<Set<String>> = context.dataStore.data.map {
        (it[K.CONTINUE_HIDDEN] ?: "").split(',').filter { id -> id.isNotBlank() }.toSet()
    }

    suspend fun hideFromContinue(itemId: String) {
        val cur = continueHiddenFlow.first().toMutableSet()
        cur.add(itemId)
        context.dataStore.edit { it[K.CONTINUE_HIDDEN] = cur.joinToString(",") }
    }

    /** Playing a book again puts it back on the shelf. */
    suspend fun unhideFromContinue(itemId: String) {
        val cur = continueHiddenFlow.first()
        if (itemId !in cur) return
        context.dataStore.edit { it[K.CONTINUE_HIDDEN] = (cur - itemId).joinToString(",") }
    }
    suspend fun setTrackScope(v: String) = context.dataStore.edit { it[K.TRACK_SCOPE] = v }
    /** The player service asks for this on every position tick, so read the mirror. */
    fun trackScopeBlocking(): String = mTrackScope
    fun localFolderBlocking(): String = mLocalFolder
    suspend fun setOfflineOnly(v: Boolean) = context.dataStore.edit { it[K.OFFLINE_ONLY] = v }
    suspend fun setLocalFolder(v: String) = context.dataStore.edit { it[K.LOCAL_FOLDER] = v }

    fun skipBackBlocking(): Int = mSkipBack
    fun skipForwardBlocking(): Int = mSkipForward
    fun downloadDirBlocking(): String = mDownloadDir
    val apiKeyFlow: Flow<String> = context.dataStore.data.map { it[K.API_KEY] ?: "" }
    val homeSectionsFlow: Flow<String> =
        context.dataStore.data.map { it[K.HOME_SECTIONS] ?: DEFAULT_SECTIONS }
    val serverFlow: Flow<String?> = context.dataStore.data.map { it[K.SERVER] }
    val usernameFlow: Flow<String?> = context.dataStore.data.map { it[K.USERNAME] }

    suspend fun serverUrl(): String? = context.dataStore.data.first()[K.SERVER]
    suspend fun token(): String? = context.dataStore.data.first()[K.TOKEN]
    fun serverUrlBlocking(): String? = mServer
    fun tokenBlocking(): String? = mToken

    suspend fun setServerUrl(v: String) = context.dataStore.edit { it[K.SERVER] = v }
    suspend fun setToken(v: String) = context.dataStore.edit { it[K.TOKEN] = v }
    suspend fun setUsername(v: String) = context.dataStore.edit { it[K.USERNAME] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.THEME] = v }
    suspend fun setAccent(v: String) = context.dataStore.edit { it[K.ACCENT] = v }
    suspend fun setProgressStyle(v: String) = context.dataStore.edit { it[K.PROGRESS_STYLE] = v }
    suspend fun setDownloadDir(v: String) = context.dataStore.edit { it[K.DOWNLOAD_DIR] = v }
    suspend fun setCoverSize(v: Int) = context.dataStore.edit { it[K.COVER_SIZE] = v.toString() }
    suspend fun setFavoritesTop(v: Boolean) = context.dataStore.edit { it[K.FAVORITES_TOP] = v }
    suspend fun setSkipBack(v: Int) = context.dataStore.edit { it[K.SKIP_BACK] = v.toString() }
    suspend fun setSkipForward(v: Int) = context.dataStore.edit { it[K.SKIP_FORWARD] = v.toString() }
    suspend fun setApiKey(v: String) = context.dataStore.edit { it[K.API_KEY] = v }
    suspend fun apiKey(): String? = context.dataStore.data.first()[K.API_KEY]
    suspend fun playbackSpeed(): Float = context.dataStore.data.first()[K.SPEED]?.toFloatOrNull() ?: 1.0f
    suspend fun setPlaybackSpeed(v: Float) = context.dataStore.edit { it[K.SPEED] = v.toString() }

    val favoritesFlow: Flow<Set<String>> = context.dataStore.data.map {
        (it[K.FAVORITES] ?: "").split(',').filter { id -> id.isNotBlank() }.toSet()
    }

    suspend fun toggleFavorite(itemId: String) {
        val cur = (context.dataStore.data.first()[K.FAVORITES] ?: "")
            .split(',').filter { it.isNotBlank() }.toMutableSet()
        if (!cur.add(itemId)) cur.remove(itemId)
        context.dataStore.edit { it[K.FAVORITES] = cur.joinToString(",") }
    }
    suspend fun setHomeSections(csv: String) = context.dataStore.edit { it[K.HOME_SECTIONS] = csv }

    suspend fun logout() = context.dataStore.edit { it.remove(K.TOKEN); it.remove(K.USERNAME) }

    /**
     * The last book that was loaded into the player. Survives the process, so when the
     * system asks the app to resume playback — a car, a headset, the media resumption
     * chip — there is something to answer with even on a cold start.
     */
    suspend fun setLastItem(itemId: String) = context.dataStore.edit { it[K.LAST_ITEM] = itemId }
    suspend fun lastItem(): String? = context.dataStore.data.first()[K.LAST_ITEM]

    /* --- positions kept on the device ---
     *
     * Every position lands here, connected or not, and is pushed to the server when there
     * is one. Nothing else in the app is allowed to be the only record of where a book got
     * to: a server that cannot be reached, or a process that is about to be killed, must
     * not cost the listener their place.
     */

    private val progressJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    val localProgressFlow: Flow<Map<String, LocalProgress>> =
        context.dataStore.data.map { decodeProgress(it[K.LOCAL_PROGRESS]) }

    suspend fun localProgress(): Map<String, LocalProgress> =
        decodeProgress(context.dataStore.data.first()[K.LOCAL_PROGRESS])

    /** Reads both the current shape and the plain `{id: seconds}` written before 1.3.2. */
    private fun decodeProgress(raw: String?): Map<String, LocalProgress> {
        if (raw.isNullOrBlank()) return emptyMap()
        runCatching { return progressJson.decodeFromString<Map<String, LocalProgress>>(raw) }
        runCatching {
            return progressJson.decodeFromString<Map<String, Double>>(raw)
                .mapValues { (_, sec) -> LocalProgress(pos = sec) }
        }
        return emptyMap()
    }

    /**
     * Read-modify-write inside a single [edit] so two saves landing together — the player's
     * 15s tick and a teardown save, say — cannot drop one of the two.
     */
    suspend fun setLocalProgress(itemId: String, currentTimeSec: Double, finished: Boolean? = null) {
        context.dataStore.edit { prefs ->
            val map = decodeProgress(prefs[K.LOCAL_PROGRESS]).toMutableMap()
            map[itemId] = LocalProgress(
                pos = currentTimeSec,
                updatedAt = System.currentTimeMillis(),
                finished = finished ?: map[itemId]?.finished ?: false,
            )
            prefs[K.LOCAL_PROGRESS] = progressJson.encodeToString<Map<String, LocalProgress>>(map)
        }
    }

    /**
     * The same write, but it does not return until the position is on disk. Teardown paths
     * have no coroutine guaranteed to outlive them, and a position saved after the process
     * dies is a position lost.
     */
    fun setLocalProgressBlocking(itemId: String, currentTimeSec: Double) =
        runBlocking { setLocalProgress(itemId, currentTimeSec) }

    companion object {
        const val DEFAULT_SECTIONS = "continue,favorites,downloaded,series,all"
        val SECTION_LABELS = mapOf(
            "continue" to "Continue Listening",
            "favorites" to "Favorites",
            "downloaded" to "Downloaded",
            "series" to "Series",
            "authors" to "Authors",
            "narrators" to "Narrators",
            "all" to "All Books",
        )
    }
}
