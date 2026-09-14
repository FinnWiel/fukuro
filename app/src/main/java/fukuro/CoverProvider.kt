package fukuro

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import okhttp3.Request
import java.io.File

/**
 * Serves book covers as `content://` uris, for Android Auto.
 *
 * The car's UI runs in another app, and it will only load artwork from a content:// or
 * android.resource:// uri: a file:// path inside our private storage is unreadable to
 * it, and it does not download http artwork on our behalf. Handing it either — which is
 * what the session used to do — leaves the browse list and the now-playing background
 * blank. So everything the session publishes goes through here instead: downloaded and
 * on-device books are served straight off disk, and server covers are fetched once into
 * the cache and served from there.
 *
 * The uri is `content://<applicationId>.covers/<itemId>`. Ids are a single path segment,
 * so ids that contain slashes (on-device books carry a document uri) survive the trip.
 */
class CoverProvider : ContentProvider() {

    companion object {
        private const val SUFFIX = ".covers"

        fun uriFor(context: Context, itemId: String): Uri =
            Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(context.packageName + SUFFIX)
                .appendPath(itemId)
                .build()

        /**
         * Resolve the file behind [uriFor].  The player uses this directly when it makes
         * the square system-artwork bitmap, which avoids decoding through a binder pipe.
         *
         * [fetchIfMissing] is false on the playback-critical path.  That path can use a
         * cover already on disk immediately; a later, background pass may fetch a server
         * cover without spending Android's short playback-resumption budget.
         */
        fun coverFileFor(context: Context, itemId: String, fetchIfMissing: Boolean): File? {
            val app = context.applicationContext as? ShelfApp ?: return null
            app.coverOverrides.coverFile(itemId)?.let { return it }
            if (LocalLibrary.isLocal(itemId)) return app.local.coverFile(itemId)
            app.downloads.localCover(itemId)?.let { return it }
            val cached = File(app.cacheDir, "autocovers/${itemId.hashCode()}.jpg")
            if (cached.exists() && cached.length() > 0) return cached
            return if (fetchIfMissing) fetchServerCover(app, itemId, cached) else null
        }

        /**
         * Covers for books that only live on the server are cached for every external
         * controller, not just Android Auto.  This must run off the main thread.
         */
        private fun fetchServerCover(app: ShelfApp, itemId: String, dest: File): File? {
            if (app.store.serverUrlBlocking().isNullOrBlank()) return null
            return try {
                dest.parentFile?.mkdirs()
                val request = Request.Builder().url(app.api.coverUrl(itemId)).build()
                app.api.http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val part = File(dest.path + ".part")
                    part.outputStream().use { out -> resp.body!!.byteStream().use { it.copyTo(out) } }
                    if (dest.exists()) dest.delete()
                    if (!part.renameTo(dest)) return null
                }
                dest.takeIf { it.exists() && it.length() > 0 }
            } catch (_: Exception) {
                null // no cover is better than a broken one; the car falls back to its placeholder
            }
        }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = coverFileForUri(uri) ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Android Auto asks for the name and size before opening. Answering from the file we
     * would serve keeps it from treating a missing cover as a stalled load.
     */
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val file = coverFileForUri(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }.toTypedArray())
        return cursor
    }

    /** Read-only: covers are written by the download and scan paths, never through here. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ) = 0

    /** The cover on disk for this id, downloading the server's copy if that's all there is. */
    private fun coverFileForUri(uri: Uri): File? {
        val appContext = context ?: return null
        val itemId = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        return coverFileFor(appContext, itemId, fetchIfMissing = true)
    }
}
