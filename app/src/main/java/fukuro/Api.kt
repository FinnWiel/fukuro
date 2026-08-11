package fukuro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Minimal Audiobookshelf REST client.
 * All calls are suspend + Dispatchers.IO; throws ApiException on non-2xx.
 */
class AbsApi(private val store: Store) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Very short-fused client used only to answer "is the server there?". */
    private val probe: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    /**
     * One 2-second probe instead of letting four library calls time out in turn —
     * this is what makes the app usable immediately when the server is away.
     */
    suspend fun reachable(): Boolean = withContext(Dispatchers.IO) {
        val base = store.serverUrl() ?: return@withContext false
        try {
            val req = Request.Builder().url(base.trimEnd('/') + "/ping").get().build()
            probe.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

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

    /**
     * Pulls the first chunk of an audio file and throws it away. That opens the
     * connection, resolves DNS/TLS and gets the server reading the file, so the
     * player has far less to do when the user actually presses play.
     */
    suspend fun warmUp(itemId: String, ino: String) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(fileUrl(itemId, ino))
                .header("Range", "bytes=0-262143")
                .get().build()
            http.newCall(req).execute().use { resp -> resp.body?.source()?.readByteArray() }
        } catch (_: Exception) { /* warming is best effort */ }
        Unit
    }

    /** Authors with their (optional) portrait; imagePath == null means no photo on the server. */
    suspend fun libraryAuthors(libraryId: String): List<AbsAuthor> =
        json.decodeFromString<AuthorsResponse>(raw("GET", "/api/libraries/$libraryId/authors")).authors

    fun authorImageUrl(authorId: String): String {
        val base = store.serverUrlBlocking()?.trimEnd('/') ?: ""
        val token = store.tokenBlocking() ?: ""
        return "$base/api/authors/$authorId/image?token=$token"
    }

    /** The item-list endpoint omits full series info; this endpoint has the real grouping. */
    suspend fun librarySeries(libraryId: String): List<AbsSeries> =
        json.decodeFromString<SeriesListResponse>(
            raw("GET", "/api/libraries/$libraryId/series?limit=200&sort=name")
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

    /**
     * Deletes a progress record. NOTE the path takes the *media progress* id, not the
     * library item id — passing an item id returns HTTP 200 and silently deletes nothing.
     */
    suspend fun resetProgress(progressId: String) {
        try { raw("DELETE", "/api/me/progress/$progressId") } catch (e: ApiException) {
            if (e.code != 404) throw e // 404 = already gone, fine
        }
    }

    suspend fun renameItem(itemId: String, newTitle: String) {
        val body = buildJsonObject {
            putJsonObject("metadata") { put("title", newTitle) }
        }.toString()
        raw("PATCH", "/api/items/$itemId/media", body)
    }

    /** One file selected for upload: name/size plus a fresh-stream provider (content resolver). */
    class UploadFile(val name: String, val size: Long, val mime: String, val open: () -> InputStream)

    /**
     * Upload a new book via POST /api/upload (multipart).
     * Uses the user's stored ABS API key when set, else the login token.
     */
    suspend fun uploadBook(
        title: String, author: String?, series: String?, files: List<UploadFile>,
    ): Unit = withContext(Dispatchers.IO) {
        val base = store.serverUrl() ?: throw ApiException(0, "No server configured")
        val auth = store.apiKey()?.takeIf { it.isNotBlank() } ?: store.token()
            ?: throw ApiException(401, "Not logged in")
        val lib = libraries().firstOrNull() ?: throw ApiException(0, "No library on server")
        val folder = lib.folders.firstOrNull() ?: throw ApiException(0, "Library has no folders")

        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title", title)
            .addFormDataPart("library", lib.id)
            .addFormDataPart("folder", folder.id)
        if (!author.isNullOrBlank()) mp.addFormDataPart("author", author)
        if (!series.isNullOrBlank()) mp.addFormDataPart("series", series)
        files.forEachIndexed { i, f ->
            val body = object : RequestBody() {
                override fun contentType() = f.mime.toMediaType()
                override fun contentLength() = f.size
                override fun writeTo(sink: BufferedSink) {
                    f.open().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                }
            }
            mp.addFormDataPart("$i", f.name, body)
        }
        val req = Request.Builder()
            .url(base.trimEnd('/') + "/api/upload")
            .header("Authorization", "Bearer $auth")
            .post(mp.build())
            .build()
        // long timeout client: big audio files
        http.newBuilder().writeTimeout(30, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()
            .newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, "Upload failed HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                }
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
