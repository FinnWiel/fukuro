package fukuro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Creates the artwork embedded in MediaSession metadata.
 *
 * Android's media surfaces reserve a square for art.  Rather than asking them to crop the
 * normal cover, make a square image with a blurred, subdued cover behind the sharp original.
 * The cache owns compressed JPEG data, not [Bitmap] instances: media metadata can keep the
 * bytes safely while every temporary bitmap is recycled as soon as it has been encoded.
 */
class SystemMediaArtwork(private val context: Context) {

    companion object {
        private const val SIZE = 512
        private const val FOREGROUND_INSET = 8
        private const val BLUR_RADIUS = 28
        private const val MAX_DECODE_DIMENSION = 1_024
        private const val JPEG_QUALITY = 90
        private const val MAX_CACHE_BYTES = 3 * 1024 * 1024
    }

    private val cache = object : LruCache<String, ByteArray>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /**
     * Returns a cached square image when its cover is already available locally.  This keeps
     * playback resumption fast; the player does the network-capable pass after it is active.
     */
    fun cachedArtworkData(itemId: String): ByteArray? = artworkData(itemId, fetchIfMissing = false)

    /** Must be called from a worker thread when [fetchIfMissing] is true. */
    fun artworkData(itemId: String, fetchIfMissing: Boolean): ByteArray? {
        val cover = CoverProvider.coverFileFor(context, itemId, fetchIfMissing) ?: return null
        val key = cacheKey(itemId, cover)
        cache.get(key)?.let { return it }

        val source = decodeCover(cover) ?: return null
        return try {
            val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            try {
                drawSquareArtwork(source, square)
                ByteArrayOutputStream().use { output ->
                    if (square.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        output.toByteArray().also { cache.put(key, it) }
                    } else {
                        null
                    }
                }
            } finally {
                square.recycle()
            }
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            source.recycle()
        }
    }

    fun clear() = cache.evictAll()

    private fun cacheKey(itemId: String, cover: File): String =
        "$itemId:${cover.absolutePath}:${cover.length()}:${cover.lastModified()}"

    /** Decode only enough source pixels for a sharp 512px foreground and a cropped backdrop. */
    private fun decodeCover(cover: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cover.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= MAX_DECODE_DIMENSION ||
            bounds.outHeight / (sample * 2) >= MAX_DECODE_DIMENSION
        ) sample *= 2
        return BitmapFactory.decodeFile(cover.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    private fun drawSquareArtwork(source: Bitmap, square: Bitmap) {
        val canvas = Canvas(square)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Center-crop the same cover to fill the background before blurring it.
        val backgroundScale = max(SIZE.toFloat() / source.width, SIZE.toFloat() / source.height)
        val backgroundWidth = source.width * backgroundScale
        val backgroundHeight = source.height * backgroundScale
        canvas.drawBitmap(
            source,
            null,
            RectF(
                (SIZE - backgroundWidth) / 2f,
                (SIZE - backgroundHeight) / 2f,
                (SIZE + backgroundWidth) / 2f,
                (SIZE + backgroundHeight) / 2f,
            ),
            paint,
        )

        // RenderEffect is a hardware-rendering effect rather than an off-screen Bitmap API.
        // A software stack blur gives this generated metadata image identical results from API
        // 26 onward, including Android Auto and manufacturers' media surfaces.
        stackBlurAndSubdue(square, BLUR_RADIUS)

        // Fit-center the unmodified source at almost the full square height.  This naturally
        // also lets genuinely square source artwork use the full foreground area.
        val foregroundSize = (SIZE - FOREGROUND_INSET * 2).toFloat()
        val foregroundScale = min(foregroundSize / source.width, foregroundSize / source.height)
        val foregroundWidth = source.width * foregroundScale
        val foregroundHeight = source.height * foregroundScale
        canvas.drawBitmap(
            source,
            null,
            RectF(
                (SIZE - foregroundWidth) / 2f,
                (SIZE - foregroundHeight) / 2f,
                (SIZE + foregroundWidth) / 2f,
                (SIZE + foregroundHeight) / 2f,
            ),
            paint,
        )
    }

    /** Strong blur plus a restrained colour treatment so vivid covers do not dominate controls. */
    private fun stackBlurAndSubdue(bitmap: Bitmap, radius: Int) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val blurred = IntArray(pixels.size)
        val window = radius * 2 + 1

        // Separable box blurs approximate a Gaussian while keeping memory and CPU bounded.
        repeat(3) {
            blurHorizontal(pixels, blurred, width, height, radius, window)
            blurVertical(blurred, pixels, width, height, radius, window)
        }

        // The last vertical pass leaves the result in pixels. Desaturate to 55%, then darken.
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val luma = (red * 0.299f + green * 0.587f + blue * 0.114f).toInt()
            val outRed = ((red * 0.55f + luma * 0.45f) * 0.62f).toInt()
            val outGreen = ((green * 0.55f + luma * 0.45f) * 0.62f).toInt()
            val outBlue = ((blue * 0.55f + luma * 0.45f) * 0.62f).toInt()
            pixels[index] = Color.rgb(outRed, outGreen, outBlue)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun blurHorizontal(
        input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, window: Int,
    ) {
        for (y in 0 until height) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = input[y * width + offset.coerceIn(0, width - 1)]
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
            }
            for (x in 0 until width) {
                output[y * width + x] = Color.rgb(red / window, green / window, blue / window)
                val remove = input[y * width + (x - radius).coerceIn(0, width - 1)]
                val add = input[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
            }
        }
    }

    private fun blurVertical(
        input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, window: Int,
    ) {
        for (x in 0 until width) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = input[offset.coerceIn(0, height - 1) * width + x]
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
            }
            for (y in 0 until height) {
                output[y * width + x] = Color.rgb(red / window, green / window, blue / window)
                val remove = input[(y - radius).coerceIn(0, height - 1) * width + x]
                val add = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                red += Color.red(add) - Color.red(remove)
                green += Color.green(add) - Color.green(remove)
                blue += Color.blue(add) - Color.blue(remove)
            }
        }
    }
}
