package fukuro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The logo's own colours, same as the launcher icon. */
private val Brown = Color(0xFF35150E)

/**
 * Startup animation: the launcher icon putting itself together. The brown disc pops in,
 * then the owl rises up into it from below — the disc clips the owl, so it really does
 * come up out of nothing rather than fading in place.
 *
 * The artwork is the launcher's own foreground vector, scaled so its 72dp safe square
 * lands exactly on the disc. That way this is the icon the user tapped, not a copy of
 * it that can drift out of sync.
 */
@Composable
fun SplashLogo(onReveal: () -> Unit = {}, onDone: () -> Unit) {
    val disc = remember { Animatable(0f) }
    val owl = remember { Animatable(1f) } // 1 = parked below the disc, 0 = home
    var leaving by remember { mutableStateOf(false) }
    val fade by animateFloatAsState(if (leaving) 0f else 1f, tween(220), label = "splashFade")

    LaunchedEffect(Unit) {
        // Wait for a real frame before starting. Composition lands on the first frame or
        // two of the process, and an animation started inside that stall opens by
        // catching up on elapsed time instead of easing in.
        withFrameNanos { }
        // overshoots to 1.15 and settles: the disc lands with a bounce
        disc.animateTo(1f, tween(300, easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)))
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(70) // the disc is already on its way; the two should read as one gesture
        // Fixed duration, not a spring. A spring covers the distance quickly and then
        // approaches home asymptotically, and that long slow tail is what looked like the
        // owl stalling just short of the top. A tween simply arrives.
        owl.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
        onReveal() // build the app underneath now, while the logo is still opaque
        delay(150)
        leaving = true
        delay(220)
        onDone()
    }

    Box(
        Modifier.fillMaxSize()
            // matches windowBackground, so there is no flash between the system's
            // launch screen and this one
            .background(Color.Black)
            .graphicsLayer { alpha = fade },
        contentAlignment = Alignment.Center
    ) {
        val size = 132.dp
        Box(
            Modifier.size(size)
                .graphicsLayer { scaleX = disc.value; scaleY = disc.value }
                .clip(CircleShape)
                .background(Brown),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                // The vector's visible area is the middle 72 of its 108 viewport, so at
                // 1.5x the disc the safe square lands exactly on the disc — the same
                // composition a round launcher mask produces. requiredSize, not size:
                // the disc's constraints would otherwise shrink it back to 132dp.
                modifier = Modifier.requiredSize(size * 1.5f).graphicsLayer {
                    translationY = owl.value * this.size.height * 0.42f
                }
            )
        }
    }
}
