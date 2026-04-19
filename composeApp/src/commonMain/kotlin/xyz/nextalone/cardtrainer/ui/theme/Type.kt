package xyz.nextalone.cardtrainer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Redesign.html uses Spectral (display) / Inter (body) / JetBrains Mono (nums).
// Without bundled TTFs we fall back to the cross-platform family primitives.
// Replacement: bundle Google Fonts via compose.components.resources and swap
// these three vals without touching call sites.
val BrandDisplayFamily: FontFamily = FontFamily.Serif
val BrandBodyFamily: FontFamily = FontFamily.SansSerif
val BrandMonoFamily: FontFamily = FontFamily.Monospace

val BrandTypography = Typography(
    // Display — Spectral-like (serif, tight tracking)
    displayLarge = TextStyle(
        fontFamily = BrandDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 1.05.em,
        letterSpacing = (-0.01).em,
    ),
    displayMedium = TextStyle(
        fontFamily = BrandDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 1.1.em,
        letterSpacing = (-0.01).em,
    ),
    displaySmall = TextStyle(
        fontFamily = BrandDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 1.15.em,
        letterSpacing = (-0.005).em,
    ),
    headlineLarge = TextStyle(
        fontFamily = BrandDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 1.2.em,
    ),
    headlineMedium = TextStyle(
        fontFamily = BrandDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 1.25.em,
    ),
    headlineSmall = TextStyle(
        fontFamily = BrandDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 1.3.em,
    ),
    titleLarge = TextStyle(
        fontFamily = BrandDisplayFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 1.3.em,
    ),
    titleMedium = TextStyle(
        fontFamily = BrandBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 1.4.em,
    ),
    titleSmall = TextStyle(
        fontFamily = BrandBodyFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 1.4.em,
    ),
    bodyLarge = TextStyle(
        fontFamily = BrandBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 1.5.em,
    ),
    bodyMedium = TextStyle(
        fontFamily = BrandBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 1.5.em,
    ),
    bodySmall = TextStyle(
        fontFamily = BrandBodyFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 1.5.em,
    ),
    labelLarge = TextStyle(
        fontFamily = BrandBodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 1.3.em,
    ),
    labelMedium = TextStyle(
        fontFamily = BrandBodyFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 1.3.em,
    ),
    // Eyebrow-style caption used for uppercase labels over titles
    labelSmall = TextStyle(
        fontFamily = BrandMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 0.14.em,
    ),
)
