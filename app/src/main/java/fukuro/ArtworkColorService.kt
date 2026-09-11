package fukuro

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns cover artwork into a dark, restrained UI colour suitable for player chrome.
 * Results are cached by media id and artwork revision, so recomposition never repeats
 * bitmap loading or colour quantization.
 */
class ArtworkColorService(context: Context) {
    private val appContext = context.applicationContext
    private val imageLoader = appContext.imageLoader
    private val cache = LruCache<String, Int>(64)

    suspend fun colorFor(
        mediaId: String,
        artworkRevision: Int,
        model: Any,
        surfaceColor: Int,
    ): Int? {
        val key = "$mediaId:$artworkRevision:${surfaceColor.toUInt()}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            cache.get(key)?.let { return@withContext it }
            val generated = runCatching {
                val request = if (model is ImageRequest) {
                    model.newBuilder().allowHardware(false).size(128).build()
                } else {
                    ImageRequest.Builder(appContext).data(model).allowHardware(false).size(128).build()
                }
                val drawable = imageLoader.execute(request).drawable as? BitmapDrawable
                    ?: return@runCatching null
                val source = selectRepresentativeColor(drawable.bitmap) ?: return@runCatching null
                normalizeForPlayer(source, surfaceColor)
            }.getOrNull()
            generated?.also { cache.put(key, it) }
        }
    }

    /** Quantize the bitmap, then score several swatches for colour and representation. */
    private fun selectRepresentativeColor(source: android.graphics.Bitmap): Int? {
        val side = 48
        val sample = android.graphics.Bitmap.createScaledBitmap(source, side, side, true)
        val pixels = IntArray(side * side)
        sample.getPixels(pixels, 0, side, 0, 0, side, side)

        // Four bits per channel gives a compact palette while preserving distinct accents.
        val buckets = HashMap<Int, LongArray>()
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 200) continue
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val key = ((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4)
            val bucket = buckets.getOrPut(key) { LongArray(4) }
            bucket[0]++
            bucket[1] += red
            bucket[2] += green
            bucket[3] += blue
        }

        val swatches = buckets.values.mapNotNull { bucket ->
            val population = bucket[0].toInt()
            if (population == 0) return@mapNotNull null
            val rgb = Color.rgb(
                (bucket[1] / population).toInt(),
                (bucket[2] / population).toInt(),
                (bucket[3] / population).toInt(),
            )
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(rgb, it) }
            Swatch(rgb, population, hsl)
        }
        if (swatches.isEmpty()) return null

        // Prefer a meaningful coloured swatch. This deliberately lets a smaller red
        // illustration beat a huge black background, while muted covers retain a choice.
        val coloured = swatches.filter { it.hsl[1] >= 0.12f && it.hsl[2] in 0.08f..0.88f }
        val candidates = if (coloured.isNotEmpty()) coloured else swatches.filter {
            it.hsl[2] in 0.08f..0.88f
        }.ifEmpty { swatches }
        val largestPopulation = candidates.maxOf { it.population }.toFloat()
        return candidates.maxByOrNull { swatch ->
            val saturation = swatch.hsl[1].coerceAtMost(0.8f) / 0.8f
            val usefulLightness = (1f - abs(swatch.hsl[2] - 0.48f) / 0.48f).coerceIn(0f, 1f)
            val representation = sqrt(swatch.population / largestPopulation)
            representation * 0.42f + saturation * 0.43f + usefulLightness * 0.15f
        }?.rgb
    }

    /** Preserve hue, tame saturation/lightness, blend into the app, then verify contrast. */
    private fun normalizeForPlayer(sourceColor: Int, surfaceColor: Int): Int {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(sourceColor, it) }
        hsl[1] = when {
            hsl[1] > 0.80f -> 0.55f
            hsl[1] > 0.65f -> 0.60f
            else -> hsl[1].coerceAtLeast(0.25f)
        }.coerceAtMost(0.65f)
        hsl[2] = hsl[2].coerceIn(0.22f, 0.40f)

        val normalized = ColorUtils.HSLToColor(hsl)
        val opaqueSurface = surfaceColor or Color.BLACK
        val blended = ColorUtils.blendARGB(opaqueSurface, normalized, 0.80f)
        return darkenForWhiteText(blended)
    }

    /** Darken only as much as needed for white foreground content to clear WCAG AA. */
    private fun darkenForWhiteText(color: Int): Int {
        var result = color or Color.BLACK
        repeat(24) {
            if (ColorUtils.calculateContrast(Color.WHITE, result) >= 4.5) return result
            result = ColorUtils.blendARGB(result, Color.BLACK, 0.06f)
        }
        return result
    }

    private data class Swatch(val rgb: Int, val population: Int, val hsl: FloatArray)
}

/** Load a cached artwork colour only when the media, artwork revision or theme changes. */
@Composable
fun rememberArtworkUiColor(
    mediaId: String?,
    artworkRevision: Int,
    model: Any?,
): ComposeColor? {
    val context = LocalContext.current
    val surfaceColor = Fukuro.colors.background
    var color by remember { mutableStateOf<ComposeColor?>(null) }
    LaunchedEffect(mediaId, artworkRevision, surfaceColor) {
        if (mediaId == null || model == null) {
            color = null
            return@LaunchedEffect
        }
        color = (context.applicationContext as ShelfApp).artworkColors.colorFor(
            mediaId = mediaId,
            artworkRevision = artworkRevision,
            model = model,
            surfaceColor = surfaceColor.toArgb(),
        )?.let { ComposeColor(it) }
    }
    return color
}
