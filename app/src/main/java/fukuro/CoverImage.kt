package fukuro

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
