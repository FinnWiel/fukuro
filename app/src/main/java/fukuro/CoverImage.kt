package fukuro

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/**
 * Image with a placeholder shown while loading and when there is nothing to show.
 * Books get the headphones glyph; authors pass [OwlPlaceholder].
 */
@Composable
fun CoverImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit = { CoverPlaceholder() },
) {
    SubcomposeAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        loading = { placeholder() },
        error = { placeholder() },
    )
}

/**
 * Several covers packed into the footprint of one, for series cards.
 *
 * One book is just its cover. Two split the square down the middle. Three — the shape a
 * trilogy takes — give the first book the whole left half and stack the other two beside
 * it, so nothing is left empty and the tiles stay a sensible shape. Four or more fill a
 * 2x2. Covers keep their full height wherever possible, since cropping a book cover's
 * width keeps the title band while cropping its height eats the author line.
 *
 * [modifier] carries the size, aspect and clip, exactly as it would for a single cover.
 */
@Composable
fun CoverMosaic(
    models: List<Any?>,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val gap = 1.5.dp
    // the gaps are this background showing through, so they read as seams, not holes
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        when (models.size) {
            0 -> CoverPlaceholder()
            1 -> CoverImage(models[0], contentDescription, Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CoverImage(models[0], contentDescription, Modifier.weight(1f).fillMaxHeight())
                CoverImage(models[1], null, Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CoverImage(models[0], contentDescription, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    CoverImage(models[1], null, Modifier.fillMaxWidth().weight(1f))
                    CoverImage(models[2], null, Modifier.fillMaxWidth().weight(1f))
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in 0..1) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        CoverImage(
                            models[row * 2],
                            if (row == 0) contentDescription else null,
                            Modifier.weight(1f).fillMaxHeight()
                        )
                        CoverImage(models[row * 2 + 1], null, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/**
 * Glyph tint for placeholders: halfway between the original light theme grey
 * (onSurfaceVariant @ 55%) and the flat black @ 45% — i.e. the theme colour
 * pulled halfway to black, at an alpha between the two.
 */
private val placeholderTint: Color
    @Composable get() = lerp(MaterialTheme.colorScheme.onSurfaceVariant, Color.Black, 0.5f)
        .copy(alpha = 0.5f)

@Composable
fun CoverPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Headphones, contentDescription = null,
            modifier = Modifier.fillMaxSize(0.4f),
            tint = placeholderTint
        )
    }
}

/**
 * Fallback for authors with no photo on the server: the Fukuro owl sitting on the
 * bottom edge (like the launcher icon), in the same muted grey as [CoverPlaceholder].
 */
@Composable
fun OwlPlaceholder() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.BottomCenter
    ) {
        Image(
            painter = painterResource(R.drawable.ic_owl),
            contentDescription = null,
            colorFilter = ColorFilter.tint(placeholderTint),
            modifier = Modifier.fillMaxWidth(0.8f)
        )
    }
}
