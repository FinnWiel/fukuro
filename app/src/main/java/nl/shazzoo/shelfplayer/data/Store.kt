package nl.shazzoo.shelfplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "shelfplayer")

/** Small persistent settings store. Blocking getters exist for non-suspend call sites (URL builders). */
class Store(private val context: Context) {
    private object K {
        val SERVER = stringPreferencesKey("server_url")
        val TOKEN = stringPreferencesKey("token")
        val USERNAME = stringPreferencesKey("username")
        val THEME = stringPreferencesKey("theme") // system | dark | light
        val HOME_SECTIONS = stringPreferencesKey("home_sections") // csv order
        val LOCAL_PROGRESS = stringPreferencesKey("local_progress") // json {itemId: currentTimeSec}
        val ACCENT = stringPreferencesKey("accent") // "dynamic" or a key from ACCENT_COLORS
        val API_KEY = stringPreferencesKey("abs_api_key")
        val SPEED = stringPreferencesKey("playback_speed")
    }

    val themeFlow: Flow<String> = context.dataStore.data.map { it[K.THEME] ?: "system" }
    val accentFlow: Flow<String> = context.dataStore.data.map { it[K.ACCENT] ?: "green" }
    val apiKeyFlow: Flow<String> = context.dataStore.data.map { it[K.API_KEY] ?: "" }
    val homeSectionsFlow: Flow<String> =
        context.dataStore.data.map { it[K.HOME_SECTIONS] ?: DEFAULT_SECTIONS }
    val serverFlow: Flow<String?> = context.dataStore.data.map { it[K.SERVER] }
    val usernameFlow: Flow<String?> = context.dataStore.data.map { it[K.USERNAME] }

    suspend fun serverUrl(): String? = context.dataStore.data.first()[K.SERVER]
    suspend fun token(): String? = context.dataStore.data.first()[K.TOKEN]
    fun serverUrlBlocking(): String? = runBlocking { serverUrl() }
    fun tokenBlocking(): String? = runBlocking { token() }

    suspend fun setServerUrl(v: String) = context.dataStore.edit { it[K.SERVER] = v }
    suspend fun setToken(v: String) = context.dataStore.edit { it[K.TOKEN] = v }
    suspend fun setUsername(v: String) = context.dataStore.edit { it[K.USERNAME] = v }
    suspend fun setTheme(v: String) = context.dataStore.edit { it[K.THEME] = v }
    suspend fun setAccent(v: String) = context.dataStore.edit { it[K.ACCENT] = v }
    suspend fun setApiKey(v: String) = context.dataStore.edit { it[K.API_KEY] = v }
    suspend fun apiKey(): String? = context.dataStore.data.first()[K.API_KEY]
    suspend fun playbackSpeed(): Float = context.dataStore.data.first()[K.SPEED]?.toFloatOrNull() ?: 1.0f
    suspend fun setPlaybackSpeed(v: Float) = context.dataStore.edit { it[K.SPEED] = v.toString() }
    suspend fun setHomeSections(csv: String) = context.dataStore.edit { it[K.HOME_SECTIONS] = csv }

    suspend fun logout() = context.dataStore.edit { it.remove(K.TOKEN); it.remove(K.USERNAME) }

    // --- local playback-position cache (used when the server is unreachable) ---
    suspend fun localProgress(): Map<String, Double> = try {
        val raw = context.dataStore.data.first()[K.LOCAL_PROGRESS] ?: "{}"
        kotlinx.serialization.json.Json.decodeFromString(raw)
    } catch (_: Exception) { emptyMap() }

    suspend fun setLocalProgress(itemId: String, currentTimeSec: Double) {
        val map = localProgress().toMutableMap()
        map[itemId] = currentTimeSec
        val enc = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<Map<String, Double>>(), map
        )
        context.dataStore.edit { it[K.LOCAL_PROGRESS] = enc }
    }

    companion object {
        const val DEFAULT_SECTIONS = "continue,downloaded,series,all"
        val SECTION_LABELS = mapOf(
            "continue" to "Continue Listening",
            "downloaded" to "Downloaded",
            "series" to "Series",
            "authors" to "Authors",
            "all" to "All Books",
        )
    }
}
