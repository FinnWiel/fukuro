package fukuro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Selectable accent colors. Key -> (name, color).
 * The accent preference may also be a literal "#RRGGBB" from the colour picker.
 */
val ACCENT_COLORS = linkedMapOf(
    "fukuro" to ("Fukuro" to Color(0xFFEF4223)), // the logo orange; app default
    "red" to ("Red" to Color(0xFFE53935)),
    "orange" to ("Orange" to Color(0xFFFB8C00)),
    "yellow" to ("Yellow" to Color(0xFFF9A825)),
    "green" to ("Green" to Color(0xFF43A047)),
    "teal" to ("Teal" to Color(0xFF00897B)),
    "blue" to ("Blue" to Color(0xFF1E88E5)),
    "indigo" to ("Indigo" to Color(0xFF3949AB)),
    "purple" to ("Purple" to Color(0xFF8E24AA)),
    "pink" to ("Pink" to Color(0xFFD81B60)),
)

const val DEFAULT_ACCENT = "fukuro"

/** Resolves an accent preference (palette key or "#RRGGBB") to a colour. */
fun accentColorOf(pref: String): Color {
    val fallback = ACCENT_COLORS.getValue(DEFAULT_ACCENT).second
    if (pref.startsWith("#")) {
        return runCatching { Color(android.graphics.Color.parseColor(pref)) }.getOrDefault(fallback)
    }
    return ACCENT_COLORS[pref]?.second ?: fallback
}

/*
 * Every slot is derived from the accent so nothing falls back to Material's
 * baseline purple/blue: buttons, chips, checkboxes, switches and sliders all
 * take the accent, and anything sitting on top of it is white.
 */

private fun darkFor(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent,
    onPrimaryContainer = Color.White,
    inversePrimary = accent,
    secondary = accent,
    onSecondary = Color.White,
    secondaryContainer = accent,
    onSecondaryContainer = Color.White,
    tertiary = accent,
    onTertiary = Color.White,
    tertiaryContainer = accent,
    onTertiaryContainer = Color.White,
    background = Color(0xFF101312),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF181C1A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF232826),
    onSurfaceVariant = Color(0xFFBFC4C1),
    surfaceTint = accent,
    outline = Color(0xFF7A807D),
    outlineVariant = Color(0xFF3A403D),
)

/** True black for OLED screens: pixels are actually off, not dark grey. */
private fun blackFor(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent,
    onPrimaryContainer = Color.White,
    inversePrimary = accent,
    secondary = accent,
    onSecondary = Color.White,
    secondaryContainer = accent,
    onSecondaryContainer = Color.White,
    tertiary = accent,
    onTertiary = Color.White,
    tertiaryContainer = accent,
    onTertiaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color.Black,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF141414), // cards/placeholders need a hair of contrast
    onSurfaceVariant = Color(0xFFBFC4C1),
    surfaceTint = accent,
    outline = Color(0xFF6E6E6E),
    outlineVariant = Color(0xFF2A2A2A),
)

private fun lightFor(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent,
    onPrimaryContainer = Color.White,
    inversePrimary = accent,
    secondary = accent,
    onSecondary = Color.White,
    secondaryContainer = accent,
    onSecondaryContainer = Color.White,
    tertiary = accent,
    onTertiary = Color.White,
    tertiaryContainer = accent,
    onTertiaryContainer = Color.White,
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF141816),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141816),
    surfaceVariant = Color(0xFFE6EAE8),
    onSurfaceVariant = Color(0xFF464B49),
    surfaceTint = accent,
    outline = Color(0xFF767E7B),
    outlineVariant = Color(0xFFC5CBC8),
)

/**
 * themePref: "system" | "light" | "dark" | "black"
 * accentPref: a key of [ACCENT_COLORS] or a literal "#RRGGBB"
 */
@Composable
fun ShelfTheme(
    themePref: String,
    accentPref: String,
    content: @Composable () -> Unit,
) {
    val dark = when (themePref) {
        "dark", "black" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val pureBlack = themePref == "black"
    val accent = accentColorOf(accentPref)
    val scheme = when {
        dark && pureBlack -> blackFor(accent)
        dark -> darkFor(accent)
        else -> lightFor(accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
