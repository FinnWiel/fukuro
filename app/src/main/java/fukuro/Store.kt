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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

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
    @Volatile private var mAutoNext: Boolean = false

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
                mAutoNext = p[K.AUTO_NEXT] ?: false
            }
        }
    }
    private object K {
        val SERVER = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val USERNAME = stringPreferencesKey("username")
        val THEME = stringPreferencesKey("theme") // system | dark | light
        val HOME_SECTIONS = stringPreferencesKey("home_sections") // csv order, pre-1.11 shelves
        val HOME_SHELVES = stringPreferencesKey("home_shelves") // json list of Shelf
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
        // "book" | "chapter" | "chapter_cover" | "chapter_stacked"
        val TRACK_SCOPE = stringPreferencesKey("track_scope")
        val CONTINUE_HIDDEN = stringPreferencesKey("continue_hidden") // csv of ids kept out of the shelf
        val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
        val AUTO_NEXT = booleanPreferencesKey("auto_next_in_series") // off: finishing stops
        val SWIPE_ACTION = stringPreferencesKey("swipe_action")      // "chapter" | "book"
        val UPDATE_LAST_CHECK = stringPreferencesKey("update_last_check") // epoch ms
        val API_KEY = stringPreferencesKey("abs_api_key")
        val SPEED = stringPreferencesKey("playback_speed")
        val LAST_ITEM = stringPreferencesKey("last_item") // what the system offers to resume
        val FAVORITES = stringPreferencesKey("favorites") // csv of item ids
        val CUSTOM_SHELF = stringPreferencesKey("custom_shelf") // json ordered mixed entries
        val LISTENING_DAYS = stringPreferencesKey("listening_days") // json {yyyy-MM-dd: seconds}
        val LISTENING_SESSIONS = stringPreferencesKey("listening_sessions") // most recent local sessions
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

    /** Finishing a book stops by default; this opts in to rolling into the next one. */
    val autoNextFlow: Flow<Boolean> = context.dataStore.data.map { it[K.AUTO_NEXT] ?: false }
    suspend fun setAutoNext(v: Boolean) = context.dataStore.edit { it[K.AUTO_NEXT] = v }
    fun autoNextBlocking(): Boolean = mAutoNext

    /** What a sideways swipe on either player does: "chapter" or "book". */
    val swipeActionFlow: Flow<String> = context.dataStore.data.map { it[K.SWIPE_ACTION] ?: "chapter" }
    suspend fun setSwipeAction(v: String) = context.dataStore.edit { it[K.SWIPE_ACTION] = v }

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
    /*
     * Home shelves. Nothing saved yet means one of two things: a fresh install,
     * which gets the designed defaults, or an existing one that only ever knew the
     * old CSV of section keys — that gets its own order carried across, so an
     * arrangement someone set up survives the upgrade. The CSV is left in place
     * either way; the first save here takes over from it for good.
     */
    private val shelfJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val homeShelvesFlow: Flow<List<Shelf>> = context.dataStore.data.map { prefs ->
        val raw = prefs[K.HOME_SHELVES]
        if (!raw.isNullOrBlank()) {
            runCatching { shelfJson.decodeFromString<List<Shelf>>(raw) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return@map it }
        }
        prefs[K.HOME_SECTIONS]?.let { shelvesFromSections(it) } ?: DEFAULT_SHELVES
    }

    suspend fun setHomeShelves(shelves: List<Shelf>) = context.dataStore.edit {
        it[K.HOME_SHELVES] = shelfJson.encodeToString(shelves.distinctBy { shelf -> shelf.id })
    }

    /** Back to the designed defaults, dropping the old CSV order with them. */
    suspend fun resetHomeShelves() = context.dataStore.edit {
        it[K.HOME_SHELVES] = shelfJson.encodeToString(DEFAULT_SHELVES)
        it.remove(K.HOME_SECTIONS)
    }
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

    val customShelfFlow: Flow<List<CustomShelfEntry>> = context.dataStore.data.map { prefs ->
        val raw = prefs[K.CUSTOM_SHELF]
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { Json.decodeFromString<List<CustomShelfEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setCustomShelf(entries: List<CustomShelfEntry>) = context.dataStore.edit {
        it[K.CUSTOM_SHELF] = Json.encodeToString(entries.distinctBy { entry -> "${entry.type}:${entry.id}" })
    }

    val listeningDaysFlow: Flow<Map<String, Double>> = context.dataStore.data.map { prefs ->
        prefs[K.LISTENING_DAYS]?.let {
            runCatching { Json.decodeFromString<Map<String, Double>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()
    }

    val listeningSessionsFlow: Flow<List<LocalListeningSession>> = context.dataStore.data.map { prefs ->
        prefs[K.LISTENING_SESSIONS]?.let {
            runCatching { Json.decodeFromString<List<LocalListeningSession>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /** Adds real elapsed playback time to a day and to one resumable local session. */
    suspend fun recordListening(
        sessionId: String,
        itemId: String,
        title: String,
        author: String,
        startedAt: Long,
        seconds: Double,
        libraryId: String,
        duration: Double,
        startTime: Double,
        currentTime: Double,
    ) {
        if (seconds <= 0.0) return
        val now = System.currentTimeMillis()
        val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toString()
        context.dataStore.edit { prefs ->
            val days = prefs[K.LISTENING_DAYS]?.let {
                runCatching { Json.decodeFromString<Map<String, Double>>(it) }.getOrDefault(emptyMap())
            }.orEmpty().toMutableMap()
            days[day] = (days[day] ?: 0.0) + seconds
            prefs[K.LISTENING_DAYS] = Json.encodeToString(days)

            val sessions = prefs[K.LISTENING_SESSIONS]?.let {
                runCatching { Json.decodeFromString<List<LocalListeningSession>>(it) }.getOrDefault(emptyList())
            }.orEmpty().toMutableList()
            val index = sessions.indexOfFirst { it.id == sessionId }
            val old = sessions.getOrNull(index)
            val updated = LocalListeningSession(
                id = sessionId, itemId = itemId, title = title, author = author,
                startedAt = startedAt, updatedAt = now,
                timeListening = (old?.timeListening ?: 0.0) + seconds,
                syncedTimeListening = old?.syncedTimeListening ?: 0.0,
                libraryId = libraryId.ifBlank { old?.libraryId.orEmpty() },
                duration = duration.takeIf { it > 0.0 } ?: old?.duration ?: 0.0,
                startTime = old?.startTime ?: startTime,
                currentTime = currentTime,
            )
            if (index >= 0) sessions[index] = updated else sessions.add(0, updated)
            prefs[K.LISTENING_SESSIONS] = Json.encodeToString(
                sessions.sortedByDescending { it.updatedAt }.take(200)
            )
        }
    }

    fun recordListeningBlocking(
        sessionId: String,
        itemId: String,
        title: String,
        author: String,
        startedAt: Long,
        seconds: Double,
        libraryId: String,
        duration: Double,
        startTime: Double,
        currentTime: Double,
    ) = runBlocking {
        recordListening(
            sessionId, itemId, title, author, startedAt, seconds,
            libraryId, duration, startTime, currentTime,
        )
    }

    /**
     * Gives old pre-sync sessions UUIDs once, then returns the persistent queue. Stable IDs
     * make Audiobookshelf's local-session upsert safe to retry after a lost response.
     */
    suspend fun listeningSessionsForSync(): List<LocalListeningSession> {
        var result = emptyList<LocalListeningSession>()
        context.dataStore.edit { prefs ->
            val sessions = prefs[K.LISTENING_SESSIONS]?.let {
                runCatching { Json.decodeFromString<List<LocalListeningSession>>(it) }.getOrDefault(emptyList())
            }.orEmpty().map { session ->
                val validUuid = runCatching { UUID.fromString(session.id) }.isSuccess
                if (validUuid) session else session.copy(id = UUID.randomUUID().toString())
            }
            prefs[K.LISTENING_SESSIONS] = Json.encodeToString(sessions)
            result = sessions
        }
        return result
    }

    /** Marks exactly the amount acknowledged by ABS and removes it from the local-only totals. */
    suspend fun markListeningSessionsSynced(acknowledged: Map<String, Double>) {
        if (acknowledged.isEmpty()) return
        context.dataStore.edit { prefs ->
            val sessions = prefs[K.LISTENING_SESSIONS]?.let {
                runCatching { Json.decodeFromString<List<LocalListeningSession>>(it) }.getOrDefault(emptyList())
            }.orEmpty()
            val days = prefs[K.LISTENING_DAYS]?.let {
                runCatching { Json.decodeFromString<Map<String, Double>>(it) }.getOrDefault(emptyMap())
            }.orEmpty().toMutableMap()
            val updated = sessions.map { session ->
                val acknowledgedTime = acknowledged[session.id] ?: return@map session
                val newSynced = maxOf(session.syncedTimeListening, acknowledgedTime)
                    .coerceAtMost(session.timeListening)
                val delta = (newSynced - session.syncedTimeListening).coerceAtLeast(0.0)
                if (delta > 0.0) {
                    val day = Instant.ofEpochMilli(session.startedAt).atZone(ZoneId.systemDefault())
                        .toLocalDate().toString()
                    days[day] = ((days[day] ?: 0.0) - delta).coerceAtLeast(0.0)
                    if (days[day] == 0.0) days.remove(day)
                }
                session.copy(syncedTimeListening = newSynced)
            }
            prefs[K.LISTENING_SESSIONS] = Json.encodeToString(updated)
            prefs[K.LISTENING_DAYS] = Json.encodeToString(days)
        }
    }

    suspend fun toggleFavorite(itemId: String) {
        val cur = (context.dataStore.data.first()[K.FAVORITES] ?: "")
            .split(',').filter { it.isNotBlank() }.toMutableSet()
        if (!cur.add(itemId)) cur.remove(itemId)
        context.dataStore.edit { it[K.FAVORITES] = cur.joinToString(",") }
    }

    /** Updates a whole collection in one DataStore write (used by series favorites). */
    suspend fun setFavorites(itemIds: Collection<String>, favorite: Boolean) {
        if (itemIds.isEmpty()) return
        val cur = (context.dataStore.data.first()[K.FAVORITES] ?: "")
            .split(',').filter { it.isNotBlank() }.toMutableSet()
        if (favorite) cur.addAll(itemIds) else cur.removeAll(itemIds.toSet())
        context.dataStore.edit { it[K.FAVORITES] = cur.joinToString(",") }
    }
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
}
