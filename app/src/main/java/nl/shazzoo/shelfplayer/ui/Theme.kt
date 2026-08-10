package nl.shazzoo.shelfplayer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Green = Color(0xFF2FBF71)
private val DarkScheme = darkColorScheme(
    primary = Green,
    secondary = Color(0xFF7BD4A8),
    background = Color(0xFF0E1613),
    surface = Color(0xFF14201B),
)
private val LightScheme = lightColorScheme(
    primary = Color(0xFF177E4E),
    secondary = Color(0xFF3E9C6F),
    background = Color(0xFFF6FBF8),
    surface = Color(0xFFFFFFFF),
)

/** themePref: "system" | "dark" | "light" */
@Composable
fun ShelfTheme(themePref: String, content: @Composable () -> Unit) {
    val dark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
