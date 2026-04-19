package xyz.nextalone.cardtrainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Root theme wrapper — applies brand palette, typography and shapes.
 * Screens reach palette via [BrandTheme.colors]; the Material3 `colorScheme`
 * is still wired for framework components (Scaffold, TopAppBar, etc.).
 */
@Composable
fun BrandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val brand = if (darkTheme) DarkBrandColors else LightBrandColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = brand.accent,
            onPrimary = Ink900,
            primaryContainer = Felt700,
            onPrimaryContainer = Ivory100,
            secondary = brand.suitBlueTong,
            onSecondary = Ivory100,
            tertiary = brand.accentBright,
            onTertiary = Ink900,
            background = brand.bg,
            onBackground = brand.fg,
            surface = brand.surface,
            onSurface = brand.fg,
            surfaceVariant = brand.surface2,
            onSurfaceVariant = brand.fgMuted,
            outline = brand.border,
            error = brand.bad,
            onError = Ivory100,
        )
    } else {
        lightColorScheme(
            primary = brand.accent,
            onPrimary = Ink900,
            primaryContainer = Ivory200,
            onPrimaryContainer = Ink900,
            secondary = brand.suitBlueTong,
            onSecondary = Ivory100,
            tertiary = brand.table,
            onTertiary = Ivory100,
            background = brand.bg,
            onBackground = brand.fg,
            surface = brand.surface,
            onSurface = brand.fg,
            surfaceVariant = brand.surface2,
            onSurfaceVariant = brand.fgMuted,
            outline = brand.border,
            error = brand.bad,
            onError = Ivory100,
        )
    }
    CompositionLocalProvider(LocalBrandColors provides brand) {
        MaterialTheme(
            colorScheme = scheme,
            typography = BrandTypography,
            shapes = BrandShapes,
            content = content,
        )
    }
}
