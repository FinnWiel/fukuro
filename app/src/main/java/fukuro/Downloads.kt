package fukuro

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import androidx.documentfile.provider.DocumentFile
import okhttp3.Request
import java.io.File

/** Per-item download state for the UI. Absent from the map = not downloading. */
data class DownloadState(val progress: Float, val error: String? = null)

/**
 * Offline downloads: stores each book under filesDir/downloads/{itemId}/
 *   - item.json   (full LibraryItem metadata, for offline browsing/playback)
 *   - cover.jpg   (artwork)
 *   - {ino}.audio (each audio file; ino is ABS's stable file id)
 * Files are written to *.part first and renamed when complete, so a killed
 * download never leaves a file that looks finished.
 */
class DownloadRepo(
    private val context: Context,
    private val api: AbsApi,
    private val store: Store,
    private val local: LocalLibrary? = null,
) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    /** Where new downloads are written (Settings choice, else app storage). */
    private fun baseDir(): File {
        val custom = store.downloadDirBlocking()
        return if (custom.isNotBlank()) File(custom) else File(context.filesDir, "downloads")
    }

    /** Every place a download may live, so switching locations doesn't orphan old books. */
    private fun baseDirs(): List<File> =
        listOf(baseDir(), File(context.filesDir, "downloads")).distinctBy { it.absolutePath }

    /** Existing folder for an item if there is one, otherwise where it would be written. */
    private fun dir(itemId: String): File =
        baseDirs().map { File(it, itemId) }.firstOrNull { it.exists() } ?: File(baseDir(), itemId)

    fun isDownloaded(itemId: String): Boolean {
        val d = dir(itemId)
        if (!File(d, "item.json").exists()) return false
        val item = localItem(itemId) ?: return false
        return item.media.audioFiles.all { File(d, "${it.ino}.audio").exists() }
    }

    fun downloadedIds(): List<String> =
        baseDirs().flatMap { base ->
            base.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }.distinct().filter { isDownloaded(it) }

    fun localItem(itemId: String): LibraryItem? = try {
        json.decodeFromString<LibraryItem>(File(dir(itemId), "item.json").readText())
    } catch (_: Exception) { null }

    fun localAudioFile(itemId: String, ino: String): File? =
        File(dir(itemId), "$ino.audio").takeIf { it.exists() }

    fun localCover(itemId: String): File? =
        File(dir(itemId), "cover.jpg").takeIf { it.exists() }

    fun sizeOnDisk(itemId: String): Long =
        dir(itemId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * When the user has picked an on-device library folder, finished downloads are
     * copied there too, so they sit alongside their own books and survive uninstall.
     * The app-storage copy stays as the one playback uses.
     */
    private fun exportToLocalFolder(item: LibraryItem, files: List<File>) {
        val treeUri = store.localFolderBlocking().takeIf { it.isNotBlank() } ?: return
        try {
            val tree = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUri)) ?: return
            val name = (item.media.metadata.title ?: item.id).replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dir = tree.findFile(name)?.takeIf { it.isDirectory } ?: tree.createDirectory(name) ?: return
            files.forEachIndexed { i, f ->
                val fileName = item.media.audioFiles.getOrNull(i)?.metadata?.filename?.takeIf { it.isNotBlank() }
                    ?: "${name}_${i + 1}.m4b"
                if (dir.findFile(fileName) != null) return@forEachIndexed // already exported
                val doc = dir.createFile("audio/mp4", fileName) ?: return@forEachIndexed
                context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                    f.inputStream().use { it.copyTo(out) }
                }
            }
        } catch (_: Exception) {
            // exporting is a bonus; a failure here must not fail the download
        }
    }

    suspend fun download(itemId: String) = withContext(Dispatchers.IO) {
        if (_states.value.containsKey(itemId)) return@withContext // already running
        _states.value = _states.value + (itemId to DownloadState(0f))
        try {
            val item = api.item(itemId)
            val d = dir(itemId).apply { mkdirs() }
            File(d, "item.json").writeText(json.encodeToString(LibraryItem.serializer(), item))

            // cover (best effort)
            try { fetchTo(api.coverUrl(itemId), File(d, "cover.jpg")) } catch (_: Exception) {}

            val files = item.media.audioFiles.sortedBy { it.index }
            val totalBytes = files.sumOf { it.metadata.size }.coerceAtLeast(1)
            var doneBytes = 0L
            for (f in files) {
                val out = File(d, "${f.ino}.audio")
                if (!out.exists() || out.length() != f.metadata.size) {
                    fetchTo(api.fileUrl(itemId, f.ino), out) { written ->
                        val p = (doneBytes + written).toFloat() / totalBytes
                        _states.value = _states.value + (itemId to DownloadState(p.coerceIn(0f, 1f)))
                    }
                }
                doneBytes += f.metadata.size
                _states.value = _states.value + (itemId to DownloadState((doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)))
            }
            exportToLocalFolder(item, files.map { File(d, "${it.ino}.audio") })
            _states.value = _states.value - itemId
        } catch (e: Exception) {
            _states.value = _states.value + (itemId to DownloadState(0f, e.message ?: "Download failed"))
        }
    }

    private inline fun fetchTo(url: String, dest: File, onProgress: (Long) -> Unit = {}) {
        val part = File(dest.parentFile, dest.name + ".part")
        api.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} for $url")
            resp.body!!.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                }
            }
        }
        if (dest.exists()) dest.delete()
        part.renameTo(dest)
    }

    fun clearError(itemId: String) { _states.value = _states.value - itemId }

    fun delete(itemId: String) {
        dir(itemId).deleteRecursively()
        _states.value = _states.value - itemId
    }
}
