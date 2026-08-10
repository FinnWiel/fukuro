package nl.shazzoo.shelfplayer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal Audiobookshelf REST client.
 * All calls are suspend + Dispatchers.IO; throws ApiException on non-2xx.
 */
class AbsApi(private val store: Store) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()

    class ApiException(val code: Int, message: String) : Exception(message)

    private suspend fun raw(method: String, path: String, body: String? = null, auth: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val base = store.serverUrl() ?: throw ApiException(0, "No server configured")
            val b = Request.Builder().url(base.trimEnd('/') + path)
            if (auth) {
                val token = store.token() ?: throw ApiException(401, "Not logged in")
                b.header("Authorization", "Bearer $token")
            }
            when (method) {
                "GET" -> b.get()
                "POST" -> b.post((body ?: "").toRequestBody(jsonType))
                "PATCH" -> b.patch((body ?: "").toRequestBody(jsonType))
                "DELETE" -> b.delete()
            }
            http.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw ApiException(resp.code, "HTTP ${resp.code}: ${text.take(200)}")
                text
            }
        }

    suspend fun login(server: String, username: String, password: String): AbsUser {
        store.setServerUrl(server)
        val body = buildJsonObject { put("username", username); put("password", password) }.toString()
        val resp = raw("POST", "/login", body, auth = false)
        val user = json.decodeFromString<LoginResponse>(resp).user
        store.setToken(user.token)
        store.setUsername(user.username)
        return user
    }

    suspend fun ping(): Boolean = try {
        raw("GET", "/ping", auth = false); true
    } catch (e: Exception) { false }

    suspend fun libraries(): List<AbsLibrary> =
        json.decodeFromString<LibrariesResponse>(raw("GET", "/api/libraries")).libraries

    suspend fun libraryItems(libraryId: String): List<LibraryItem> =
        json.decodeFromString<ItemsResponse>(
            raw("GET", "/api/libraries/$libraryId/items?limit=1000&sort=media.metadata.title&minified=0")
        ).results

    suspend fun item(itemId: String): LibraryItem =
        json.decodeFromString(raw("GET", "/api/items/$itemId?expanded=1"))

    suspend fun me(): MeResponse = json.decodeFromString(raw("GET", "/api/me"))

    suspend fun updateProgress(itemId: String, currentTime: Double, duration: Double) {
        val progress = if (duration > 0) currentTime / duration else 0.0
        val body = buildJsonObject {
            put("currentTime", currentTime); put("duration", duration); put("progress", progress)
        }.toString()
        raw("PATCH", "/api/me/progress/$itemId", body)
    }

    suspend fun markFinished(itemId: String, finished: Boolean) {
        val body = buildJsonObject { put("isFinished", finished) }.toString()
        raw("PATCH", "/api/me/progress/$itemId", body)
    }

    suspend fun resetProgress(itemId: String) {
        try { raw("DELETE", "/api/me/progress/$itemId") } catch (e: ApiException) {
            if (e.code != 404) throw e // 404 = no progress yet, fine
        }
    }

    /** Direct stream URL for one audio file of an item (token as query for ExoPlayer/Coil). */
    fun fileUrl(itemId: String, ino: String): String {
        val base = store.serverUrlBlocking()?.trimEnd('/') ?: ""
        val token = store.tokenBlocking() ?: ""
        return "$base/api/items/$itemId/file/$ino?token=$token"
    }

    fun coverUrl(itemId: String): String {
        val base = store.serverUrlBlocking()?.trimEnd('/') ?: ""
        val token = store.tokenBlocking() ?: ""
        return "$base/api/items/$itemId/cover?token=$token&width=400"
    }
}
