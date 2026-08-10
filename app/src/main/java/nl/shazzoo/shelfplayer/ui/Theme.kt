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

/** Selectable accent colors. Key -> (name, color). "dynamic" = Material You wallpaper colors. */
val ACCENT_COLORS = linkedMapOf(
    "green" to ("Green" to Color(0xFF2FBF71)),
    "blue" to ("Blue" to Color(0xFF3B82F6)),
    "purple" to ("Purple" to Color(0xFF8B5CF6)),
    "red" to ("Red" to Color(0xFFEF4444)),
    "orange" to ("Orange" to Color(0xFFF97316)),
    "pink" to ("Pink" to Color(0xFFEC4899)),
    "teal" to ("Teal" to Color(0xFF14B8A6)),
)

private fun darkFor(accent: Color) = darkColorScheme(
    primary = accent,
    secondary = accent.copy(alpha = 0.8f),
    background = Color(0xFF101312),
    surface = Color(0xFF181C1A),
    surfaceVariant = Color(0xFF232826),
)

private fun lightFor(accent: Color) = lightColorScheme(
    primary = accent,
    secondary = accent.copy(alpha = 0.8f),
    background = Color(0xFFF8FAF9),
    surface = Color(0xFFFFFFFF),
)

/**
 * themePref: "system" | "dark" | "light"
 * accentPref: "dynamic" (Material You, Android 12+) or a key of [ACCENT_COLORS]
 */
@Composable
fun ShelfTheme(themePref: String, accentPref: String, content: @Composable () -> Unit) {
    val dark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val scheme = if (accentPref == "dynamic" && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val accent = ACCENT_COLORS[accentPref]?.second ?: ACCENT_COLORS.getValue("green").second
        if (dark) darkFor(accent) else lightFor(accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
