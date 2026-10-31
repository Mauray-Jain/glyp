@file:OptIn(ExperimentalTextApi::class)

package com.mauray.glyphstudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mauray.glyphstudio.R

val NothingRed = Color(0xFFD71921)
/** Card / button fill, as in Nothing X and Settings. */
val Surface1 = Color(0xFF1B1B1B)
val Surface2 = Color(0xFF262626)
val Hairline = Color(0xFF333333)
val Muted = Color(0xFF8C8C8C)

// Open-licence stand-ins for Nothing's type: Newsreader ~ NType 82 Headline,
// Doto ~ NDot (display sizes only), IBM Plex Mono ~ NType 82 Mono, Inter for body copy.
val Serif = FontFamily(
    Font(R.font.newsreader, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(330), FontVariation.Setting("opsz", 72f))),
    Font(R.font.newsreader, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("opsz", 72f))),
)
val Dot = FontFamily(
    // Fully round dots (ROND 100) like NDot; square dots turn ":" and "." into crosses.
    Font(R.font.doto, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.Setting("ROND", 100f))),
)
val Mono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
)
val Sans = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private val colors = darkColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    secondary = Color.White,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Surface1,
    onSurfaceVariant = Muted,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface1,
    surfaceContainerHighest = Surface2,
    secondaryContainer = Surface2,
    onSecondaryContainer = Color.White,
    outline = Hairline,
    outlineVariant = Hairline,
)

private val base = Typography()
private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Light, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = base.titleMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp),
    titleSmall = base.titleSmall.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = Sans),
    bodyMedium = base.bodyMedium.copy(fontFamily = Sans),
    bodySmall = base.bodySmall.copy(fontFamily = Sans, lineHeight = 16.sp),
    labelLarge = base.labelLarge.copy(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
    labelMedium = base.labelMedium.copy(fontFamily = Sans, fontWeight = FontWeight.Medium),
    // Small caps captions, status and values: monospace, like NType 82 Mono in Nothing OS.
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

/**
 * Dot-matrix display type. Doto's dots only read cleanly when large, so it is kept for big
 * numerals and monograms, never body-size text.
 */
val DotDisplay = TextStyle(fontFamily = Dot, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = 1.sp)

/**
 * A time like "0:03.56" in dot-matrix digits. Doto draws ":" and "." as little crosses, so the
 * separators are set in the mono face instead.
 */
fun dotClock(text: String): AnnotatedString = buildAnnotatedString {
    for (c in text) {
        if (c.isDigit()) append(c) else withStyle(SpanStyle(fontFamily = Mono, fontWeight = FontWeight.Medium)) { append(c) }
    }
}

@Composable
fun GlyphStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
