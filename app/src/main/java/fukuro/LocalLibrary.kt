package fukuro

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Books that live in a folder on the phone rather than on the server.
 *
 * The user picks a folder with the system picker (a SAF tree uri); every subfolder
 * containing audio counts as one book, and loose audio files at the top level each
 * count as a book of their own. Results are cached to disk so the library shows up
 * instantly on the next launch without rescanning.
 *
 * Items are ordinary [LibraryItem]s with an id of `local:<uri>` so the rest of the
 * app — player, downloads, favourites, progress — treats them like any other book.
 */
class LocalLibrary(private val context: Context, private val store: Store) {

    companion object {
        const val PREFIX = "local:"
        private const val MAX_BOOKS = 2000
        private const val MAX_FILES_PER_BOOK = 200
        private val AUDIO = setOf("m4b", "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
        fun isLocal(itemId: String) = itemId.startsWith(PREFIX)
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = false }
    private val cacheFile = File(context.filesDir, "local_library.json")
    private val coverDir = File(context.filesDir, "localcovers").apply { mkdirs() }

    @Volatile private var cached: List<LibraryItem>? = null

    /** Last scan result; cheap, safe to call from anywhere including the player service. */
    fun items(): List<LibraryItem> {
        cached?.let { return it }
        val fromDisk = try {
            if (cacheFile.exists()) json.decodeFromString<List<LibraryItem>>(cacheFile.readText()) else emptyList()
        } catch (_: Exception) { emptyList() }
        cached = fromDisk
        return fromDisk
    }

    fun coverFile(itemId: String): File? =
        File(coverDir, itemId.removePrefix(PREFIX).hashCode().toString() + ".jpg").takeIf { it.exists() }

    /** The playable uri for one file of a local book — the ino *is* the document uri. */
    fun audioUri(ino: String): Uri? = runCatching { Uri.parse(ino) }.getOrNull()

    /**
     * Walks the chosen folder and rebuilds the cache. Never throws: a bad file, a
     * huge tree or an out-of-memory while reading tags must not take the app down.
     */
    suspend fun rescan(): List<LibraryItem> = withContext(Dispatchers.IO) {
        try { rescanInner() } catch (t: Throwable) { items() }
    }

    private suspend fun rescanInner(): List<LibraryItem> = withContext(Dispatchers.IO) {
        val treeUriStr = store.localFolderFlow.first()
        if (treeUriStr.isBlank()) {
            cached = emptyList(); cacheFile.delete(); return@withContext emptyList()
        }
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUriStr)) }.getOrNull()
            ?: return@withContext emptyList()

        val books = mutableListOf<LibraryItem>()
        val root = try { tree.listFiles() } catch (_: Throwable) { emptyArray() }

        // each SUBfolder holding audio is one book
        root.filter { it.isDirectory }.forEach { collectBooks(it, books, depth = 1) }

        // audio sitting loose in the chosen folder: one book per file.
        // (The root must not also be treated as a single book — that produced two
        // entries sharing the first file's id, and duplicate keys crash the lists.)
        root.filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in AUDIO }
            .forEach { f -> buildBook(f.name ?: "Unknown", listOf(f))?.let { books.add(it) } }

        // last line of defence: ids must be unique for the lazy lists
        val sorted = books.distinctBy { it.id }
            .sortedBy { it.media.metadata.title?.lowercase() ?: "" }
        cached = sorted
        runCatching { cacheFile.writeText(json.encodeToString(sorted)) }
        sorted
    }

    private fun collectBooks(dir: DocumentFile, out: MutableList<LibraryItem>, depth: Int) {
        if (depth > 4) return          // don't walk forever on deep trees
        if (out.size >= MAX_BOOKS) return
        val children = try { dir.listFiles() } catch (_: Throwable) { return }
        val audio = children.filter { it.isFile && it.name?.substringAfterLast('.', "")?.lowercase() in AUDIO }
        if (audio.isNotEmpty()) {
            buildBook(dir.name ?: "Unknown", audio.sortedBy { it.name ?: "" })?.let { out.add(it) }
        }
        children.filter { it.isDirectory }.forEach { collectBooks(it, out, depth + 1) }
    }

    /** Reads tags from the first file and totals the durations. Never throws. */
    private fun buildBook(folderName: String, files: List<DocumentFile>): LibraryItem? =
        try { buildBookInner(folderName, files) } catch (_: Throwable) { null }

    private fun buildBookInner(folderName: String, files: List<DocumentFile>): LibraryItem? {
        if (files.isEmpty()) return null
        val id = PREFIX + (files.first().uri.toString())
        var title: String? = null
        var author: String? = null
        var narrator: String? = null
        var year: String? = null
        var totalSec = 0.0
        val audioFiles = mutableListOf<AudioFile>()

        files.take(MAX_FILES_PER_BOOK).forEachIndexed { i, doc ->
            val mmr = MediaMetadataRetriever()
            var durSec = 0.0
            try {
                mmr.setDataSource(context, doc.uri)
                durSec = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) / 1000.0
                if (i == 0) {
                    title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    author = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    narrator = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    mmr.embeddedPicture?.let { bytes ->
                        runCatching {
                            File(coverDir, id.removePrefix(PREFIX).hashCode().toString() + ".jpg")
                                .writeBytes(bytes)
                        }
                    }
                }
            } catch (_: Throwable) {
                // unreadable file, or OOM pulling embedded art: list it anyway.
                // Throwable, not Exception — OutOfMemoryError here used to kill the app.
            } finally {
                runCatching { mmr.release() }
            }
            totalSec += durSec
            audioFiles.add(
                AudioFile(
                    index = i,
                    ino = doc.uri.toString(),
                    duration = durSec,
                    metadata = FileMeta(filename = doc.name ?: "", size = doc.length())
                )
            )
        }

        val cleanTitle = title?.takeIf { it.isNotBlank() }
            ?: folderName.substringBeforeLast('.').trim()
        return LibraryItem(
            id = id,
            relPath = folderName,
            addedAt = files.first().lastModified(),
            media = Media(
                metadata = Metadata(
                    title = cleanTitle,
                    authorName = author?.takeIf { it.isNotBlank() },
                    narratorName = narrator?.takeIf { it.isNotBlank() },
                    publishedYear = year?.takeIf { it.isNotBlank() },
                ),
                duration = totalSec,
                numAudioFiles = audioFiles.size,
                audioFiles = audioFiles,
            )
        )
    }
}
