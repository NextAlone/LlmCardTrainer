package xyz.nextalone.cardtrainer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extended design tokens beyond the Material3 ColorScheme. Screens reach these
 * via [BrandTheme.colors] so the palette stays swappable between light/dark.
 */
@Immutable
data class BrandColors(
    // Backgrounds / surfaces
    val bg: Color,
    val bgSunk: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    // Foreground ink
    val fg: Color,
    val fgMuted: Color,
    val fgSubtle: Color,
    // Card-table felt (used by FeltSurface radial gradient)
    val table: Color,
    val tableDeep: Color,
    val tableHighlight: Color,
    // Card face ivory (used by PlayingCardView / MahjongTileView)
    val ivoryTop: Color,
    val ivoryMid: Color,
    val ivoryBottom: Color,
    val ivoryEdge: Color,
    // Suit colors
    val suitRed: Color,
    val suitBlack: Color,
    val suitGreenWan: Color,   // 万 (red) — kept named like css
    val suitGreenTiao: Color,  // 条 (green)
    val suitBlueTong: Color,   // 筒 (blue)
    // Accents
    val accent: Color,
    val accentBright: Color,
    val accentSoft: Color,
    // Signals
    val good: Color,
    val bad: Color,
    val warn: Color,
    val isDark: Boolean,
)

internal val LightBrandColors = BrandColors(
    bg = Felt100,
    bgSunk = Felt200,
    surface = Ivory100,
    surface2 = Ivory200,
    border = Color(0x33094120), // felt-700 @ 20%
    fg = Ink900,
    fgMuted = Ink600,
    fgSubtle = Ink500,
    table = Felt700,
    tableDeep = Felt800,
    tableHighlight = Felt600,
    ivoryTop = Ivory100,
    ivoryMid = Ivory200,
    ivoryBottom = Ivory300,
    ivoryEdge = Ivory500,
    suitRed = SuitRed,
    suitBlack = SuitBlack,
    suitGreenWan = SuitRed,
    suitGreenTiao = SuitGreen,
    suitBlueTong = SuitBlue,
    accent = Brass600,
    accentBright = Brass400,
    accentSoft = Brass500,
    good = Good,
    bad = Bad,
    warn = Warn,
    isDark = false,
)

internal val DarkBrandColors = BrandColors(
    bg = Felt900,
    bgSunk = Color(0xFF041B0B),
    surface = Color(0xFF13331E),
    surface2 = Color(0xFF0E2917),
    border = Color(0x26F9F1E2), // ivory-200 @ ~15%
    fg = Ivory100,
    fgMuted = Ivory400,
    fgSubtle = Ivory500,
    table = Felt800,
    tableDeep = Color(0xFF041B0B),
    tableHighlight = Felt700,
    ivoryTop = Ivory100,
    ivoryMid = Ivory200,
    ivoryBottom = Ivory300,
    ivoryEdge = Ivory500,
    suitRed = SuitRed,
    suitBlack = SuitBlack,
    suitGreenWan = SuitRed,
    suitGreenTiao = SuitGreen,
    suitBlueTong = SuitBlue,
    accent = Brass500,
    accentBright = Brass400,
    accentSoft = Brass600,
    good = Good,
    bad = Bad,
    warn = Warn,
    isDark = true,
)

internal val LocalBrandColors = staticCompositionLocalOf { LightBrandColors }

/** Convenience accessor mirroring `MaterialTheme.colorScheme`. */
object BrandTheme {
    val colors: BrandColors
        @Composable
        @ReadOnlyComposable
        get() = LocalBrandColors.current
}
