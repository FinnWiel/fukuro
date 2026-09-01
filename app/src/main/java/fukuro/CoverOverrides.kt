package fukuro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Device-local cover overrides picked by the user. */
class CoverOverrides(private val context: Context) {
    private val dir = File(context.filesDir, "customcovers").apply { mkdirs() }

    fun coverFile(itemId: String): File? =
        fileFor(itemId).takeIf { it.exists() && it.length() > 0 }

    fun hasCover(itemId: String): Boolean = coverFile(itemId) != null

    fun setCover(itemId: String, uri: Uri) {
        val dest = fileFor(itemId)
        val part = File(dest.parentFile, dest.name + ".part")
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalStateException("Selected file is not an image")
        try {
            part.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw IllegalStateException("Could not encode cover")
                }
            }
        } finally {
            bitmap.recycle()
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IllegalStateException("Could not save cover")
    }

    fun clearCover(itemId: String) {
        fileFor(itemId).delete()
    }

    private fun fileFor(itemId: String): File =
        File(dir, itemId.hashCode().toString() + ".cover")
}
