package fukuro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the widgets draw.
 *
 * Kept on disk rather than in memory: a widget is usually asked to redraw when the app
 * process is long gone — hours later, or after a reboot — and it has to have something
 * to show without waking anything up.
 */
@Serializable
data class NowPlaying(
    val itemId: String = "",
    val title: String = "",
    val author: String = "",
    val isPlaying: Boolean = false,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
) {
    val progress: Float
        get() = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
}

object WidgetData {
    private val json = Json { ignoreUnknownKeys = true }

    private fun stateFile(c: Context) = File(c.filesDir, "now_playing.json")
    private fun coverFile(c: Context) = File(c.filesDir, "widget_cover.jpg")

    fun read(c: Context): NowPlaying? = runCatching {
        json.decodeFromString<NowPlaying>(stateFile(c).readText())
    }.getOrNull()?.takeIf { it.itemId.isNotBlank() }

    fun write(c: Context, np: NowPlaying) {
        runCatching { stateFile(c).writeText(json.encodeToString(NowPlaying.serializer(), np)) }
    }

    /** The cached cover, or null when there has never been one to cache. */
    fun cover(c: Context): Bitmap? = runCatching {
        coverFile(c).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    }.getOrNull()

    fun saveCover(c: Context, bytes: ByteArray) {
        decodeScaled(bytes, WIDGET_COVER_PX)?.let { store(c, it) }
    }

    fun saveCover(c: Context, source: File) {
        runCatching { source.readBytes() }.getOrNull()?.let { saveCover(c, it) }
    }

    private fun store(c: Context, bmp: Bitmap) {
        runCatching {
            coverFile(c).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
    }

    /**
     * Widgets travel to the launcher as a Bundle, and an oversized bitmap silently kills
     * the whole update, so covers are cut down to something a widget actually needs.
     */
    const val WIDGET_COVER_PX = 320

    fun decodeScaled(bytes: ByteArray, targetPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    /** Square centre crop, for shortcut icons where the launcher masks the edges anyway. */
    fun squareCrop(src: Bitmap, sizePx: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side
        )
        return Bitmap.createScaledBitmap(cropped, sizePx, sizePx, true)
    }
}
