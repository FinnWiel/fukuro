package fukuro

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
 * take the accent, and foreground text is chosen for contrast with that accent.
 */

/** Black or white, chosen from the actual colour rather than assuming every accent is dark. */
private fun textOn(accent: Color): Color = if (accent.luminance() > 0.42f) Color.Black else Color.White

private fun darkFor(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = textOn(accent),
    primaryContainer = accent,
    onPrimaryContainer = textOn(accent),
    inversePrimary = accent,
    secondary = accent,
    onSecondary = textOn(accent),
    secondaryContainer = accent,
    onSecondaryContainer = textOn(accent),
    tertiary = accent,
    onTertiary = textOn(accent),
    tertiaryContainer = accent,
    onTertiaryContainer = textOn(accent),
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
    onPrimary = textOn(accent),
    primaryContainer = accent,
    onPrimaryContainer = textOn(accent),
    inversePrimary = accent,
    secondary = accent,
    onSecondary = textOn(accent),
    secondaryContainer = accent,
    onSecondaryContainer = textOn(accent),
    tertiary = accent,
    onTertiary = textOn(accent),
    tertiaryContainer = accent,
    onTertiaryContainer = textOn(accent),
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
    onPrimary = textOn(accent),
    primaryContainer = accent,
    onPrimaryContainer = textOn(accent),
    inversePrimary = accent,
    secondary = accent,
    onSecondary = textOn(accent),
    secondaryContainer = accent,
    onSecondaryContainer = textOn(accent),
    tertiary = accent,
    onTertiary = textOn(accent),
    tertiaryContainer = accent,
    onTertiaryContainer = textOn(accent),
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

/* ---------------------------------------------------------------------------
 * Design tokens
 *
 * The app's visual language is flat and cover-led: every surface is a plain
 * theme colour with a 1dp hairline border, and the accent is used sparingly.
 * Composables read these named tokens instead of writing literal hex or
 * one-off dimensions, so the whole app moves together when a value changes.
 * ------------------------------------------------------------------------- */

/** Every colour the flat design language names. */
@Immutable
data class FukuroColors(
    val background: Color,
    /** Cards, chips, pills. */
    val surface: Color,
    /** The 1dp hairline that separates a surface from the background. */
    val outline: Color,
    val onBackground: Color,
    /** Secondary text. */
    val onSurfaceVariant: Color,
    /** Metadata under covers. */
    val tertiaryText: Color,
    val accent: Color,
    val onAccent: Color,
    /** Unfilled part of any progress bar. */
    val track: Color,
    val onlineDot: Color,
    val offlineDot: Color,
    /** Drop shadow under the hero cover. */
    val coverShadow: Color,
    /** Strip a cover's progress bar sits on, so it reads over any artwork. */
    val coverProgressStrip: Color,
    val isDark: Boolean,
) {
    /**
     * The mini player and nav bar sit on this scrim in both themes, which is why
     * their content is always light-on-dark.
     */
    val bottomScrim: Brush
        get() = Brush.verticalGradient(
            0f to Color.Transparent,
            0.45f to Color(0x99000000),
            1f to Color(0xE8000000),
        )

    /** Nav bar and mini player content, over [bottomScrim]. */
    val onScrim: Color get() = Color.White
    val onScrimDim: Color get() = Color(0x8CFFFFFF)     // white @ 55%
    val onScrimMuted: Color get() = Color(0xB3FFFFFF)   // white @ 70%
    val miniPlayerTrack: Color get() = Color(0x47FFFFFF) // white @ 28%
}

/** Every size, radius and gap the design names. Theme-independent. */
object FukuroDims {
    val screenPadding = 16.dp

    /** Gap above a shelf title, and below it before the shelf's content. */
    val shelfTitleTop = 12.dp
    val shelfTitleBottom = 8.dp

    val headerHeight = 44.dp
    val chipGap = 8.dp
    val chipPaddingH = 13.dp
    val chipPaddingV = 7.dp
    val statusDot = 7.dp

    val heroHeight = 144.dp
    val heroRadius = 20.dp
    val heroPadding = 16.dp
    val heroCoverWidth = 76.dp
    val heroCoverHeight = 112.dp
    val heroCoverRadius = 8.dp
    val heroCoverElevation = 10.dp
    val heroPlayButton = 52.dp
    val heroPlayIcon = 30.dp

    val coverRadius = 6.dp
    val carouselCellWidth = 96.dp
    val carouselCoverHeight = 108.dp
    val carouselGap = 12.dp

    val rowHeight = 86.dp
    val rowCoverWidth = 52.dp
    val rowCoverHeight = 78.dp
    val rowGap = 10.dp
    val rowContentGap = 12.dp

    val heroProgress = 4.dp
    val coverProgress = 3.dp
    val segmentProgress = 6.dp
    val segmentGap = 3.dp

    val icon = 24.dp
    val navHeight = 60.dp
    val navIcon = 24.dp
    /** The active nav icon grows by this much. */
    const val navActiveScale = 1.18f

    val miniPlayerMargin = 6.dp
    val miniPlayerRadius = 10.dp
    val miniPlayerCover = 46.dp
    val miniPlayerPlayIcon = 32.dp
    val miniPlayerProgress = 3.dp

    /** Smallest comfortable touch target. */
    val touchTarget = 44.dp

    /* Stats is not in the design frames, so its tiles and chart borrow these. */
    val tileRadius = 12.dp
    val chartHeight = 150.dp
    val chartBarRadius = 4.dp

    /** What a scrolling list must leave clear at the bottom for the floating chrome. */
    val chromeWithMiniPlayer = 140.dp
    val chromeNavOnly = 84.dp

    fun chromeHeight(miniPlayerVisible: Boolean) =
        if (miniPlayerVisible) chromeWithMiniPlayer else chromeNavOnly
}

/** The type scale, as roles rather than sizes. Roboto is the platform default. */
object FukuroType {
    val greeting = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
    val shelfTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp)
    val heroTitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.7.sp)
    val overline = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
    val rowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val captionTitle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 15.6.sp)
    val captionMeta = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
    val chip = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val chipSelected = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val navLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)
    val miniTitle = TextStyle(fontSize = 14.sp, letterSpacing = 0.25.sp)
    val miniSubtitle = TextStyle(fontSize = 12.sp, letterSpacing = 0.4.sp)
    /** Title printed on a cover placeholder, in the frames' cover-art position. */
    val coverLabel = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.65.sp)
    /* Stats headline figures — the one place numbers are the subject of the page. */
    val display = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
    val statValue = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
}

private fun darkTokens(accent: Color) = FukuroColors(
    background = Color(0xFF101312),
    surface = Color(0xFF181C1A),
    outline = Color(0xFF232826),
    onBackground = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFFBFC4C1),
    tertiaryText = Color(0xFF9AA09D),
    accent = accent,
    onAccent = textOn(accent),
    track = Color.White.copy(alpha = 0.18f),
    onlineDot = Color(0xFF2FBF71),
    offlineDot = Color(0xFFE5484D),
    coverShadow = Color.Black.copy(alpha = 0.45f),
    coverProgressStrip = Color.Black.copy(alpha = 0.45f),
    isDark = true,
)

/** Pure black keeps the same language; only the two greys drop to black. */
private fun blackTokens(accent: Color) = darkTokens(accent).copy(
    background = Color.Black,
    surface = Color.Black,
    outline = Color(0xFF1E2220),
)

private fun lightTokens(accent: Color) = FukuroColors(
    background = Color(0xFFF8FAF9),
    surface = Color(0xFFFFFFFF),
    outline = Color(0xFFE6EAE8),
    onBackground = Color(0xFF141816),
    onSurfaceVariant = Color(0xFF464B49),
    tertiaryText = Color(0xFF6B706E),
    accent = accent,
    onAccent = textOn(accent),
    track = Color(0xFF141816).copy(alpha = 0.12f),
    onlineDot = Color(0xFF2FBF71),
    offlineDot = Color(0xFFE5484D),
    coverShadow = Color(0xFF141816).copy(alpha = 0.22f),
    // the strip is over artwork, not over the page, so it stays dark in both themes
    coverProgressStrip = Color.Black.copy(alpha = 0.45f),
    isDark = false,
)

private val LocalFukuroColors = staticCompositionLocalOf {
    darkTokens(ACCENT_COLORS.getValue(DEFAULT_ACCENT).second)
}

/** Entry point for the design tokens: `Fukuro.colors`, `Fukuro.dims`, `Fukuro.type`. */
object Fukuro {
    val colors: FukuroColors
        @Composable @ReadOnlyComposable get() = LocalFukuroColors.current
    val dims get() = FukuroDims
    val type get() = FukuroType
}

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
    val tokens = when {
        dark && pureBlack -> blackTokens(accent)
        dark -> darkTokens(accent)
        else -> lightTokens(accent)
    }
    CompositionLocalProvider(LocalFukuroColors provides tokens) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Wraps [content] in a fixed theme. Only for `@Preview`s, which have no store to
 * read the user's preferences from.
 */
@Composable
fun FukuroPreviewTheme(dark: Boolean, content: @Composable () -> Unit) =
    ShelfTheme(if (dark) "dark" else "light", DEFAULT_ACCENT, content)
