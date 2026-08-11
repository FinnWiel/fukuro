package fukuro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Last successful server response, kept on disk so the library appears instantly
 * on launch — including when the server is unreachable — instead of waiting on
 * network calls that will time out.
 */
@Serializable
data class CachedLibrary(
    val items: List<LibraryItem> = emptyList(),
    val series: List<AbsSeries> = emptyList(),
    val authors: List<AbsAuthor> = emptyList(),
    val progress: List<MediaProgress> = emptyList(),
)

class LibraryCache(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val file = File(context.filesDir, "library_cache.json")

    suspend fun read(): CachedLibrary? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) null else json.decodeFromString<CachedLibrary>(file.readText())
        } catch (_: Exception) { null }
    }

    suspend fun write(c: CachedLibrary) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(c)) }
        Unit
    }

    fun clear() { runCatching { file.delete() } }
}
