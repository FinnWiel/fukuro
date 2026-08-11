package fukuro

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Books and series pinned to the home screen.
 *
 * The icon is baked at pin time and the launcher keeps it, so a shortcut still shows its
 * cover with no server in sight. Tapping one opens the app on that book — see the
 * fukuro:// links MainActivity handles.
 */
object Shortcuts {

    suspend fun pinBook(context: Context, item: LibraryItem, cover: Any?, http: OkHttpClient): Boolean =
        pin(
            context,
            id = "book_${item.id}",
            label = item.media.metadata.title ?: "Book",
            uri = "fukuro://book/${item.id}",
            cover = cover,
            http = http,
        )

    suspend fun pinSeries(context: Context, series: AbsSeries, cover: Any?, http: OkHttpClient): Boolean =
        pin(
            context,
            id = "series_${series.id}",
            label = series.name,
            uri = "fukuro://series/${series.id}",
            cover = cover,
            http = http,
        )

    private suspend fun pin(
        context: Context,
        id: String,
        label: String,
        uri: String,
        cover: Any?,
        http: OkHttpClient,
    ): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val bitmap = withContext(Dispatchers.IO) { loadCover(cover, http) }
        val icon = bitmap
            ?.let { IconCompat.createWithAdaptiveBitmap(WidgetData.squareCrop(it, 300)) }
            ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(uri)
        }
        val info = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label.take(24))
            .setLongLabel(label.take(48))
            .setIcon(icon)
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }
            .getOrDefault(false)
    }

    /** The cover model is a File for on-device books and a URL for everything else. */
    private fun loadCover(cover: Any?, http: OkHttpClient): Bitmap? = runCatching {
        val bytes = when (cover) {
            is File -> cover.readBytes()
            is String -> http.newCall(Request.Builder().url(cover).build()).execute()
                .use { resp -> if (resp.isSuccessful) resp.body?.bytes() else null }
            else -> null
        } ?: return null
        WidgetData.decodeScaled(bytes, 300)
    }.getOrNull()
}
