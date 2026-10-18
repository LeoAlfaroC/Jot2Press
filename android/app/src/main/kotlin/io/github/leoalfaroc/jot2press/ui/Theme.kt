package io.github.leoalfaroc.jot2press.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.leoalfaroc.jot2press.R

/**
 * Two voices. `you` is what the user writes. `app` is everything the app says: the day, times, buttons, the caret.
 * The scheme is fixed: it does not derive from the wallpaper.
 */
@Immutable
data class Jot2PressColors(
    val you: Color,
    val app: Color,
    val onApp: Color,
    val sheet: Color,
    /** On `Confirm discard` and `Confirm delete`, and on what the user has to fix. */
    val danger: Color,
    /** Hairlines and secondary text on the settings and queue screens. */
    val quiet: Color,
)

val LightColors = Jot2PressColors(
    you = Color(0xFF15171A),
    app = Color(0xFF14532D),
    onApp = Color(0xFFFFFFFF),
    sheet = Color(0xFFFFFFFF),
    danger = Color(0xFFC8102E),
    quiet = Color(0xFF5C6066),
)

val DarkColors = Jot2PressColors(
    you = Color(0xFFF1F1EE),
    app = Color(0xFF9BD8A8),
    onApp = Color(0xFF121416),
    sheet = Color(0xFF121416),
    danger = Color(0xFFFF8C94),
    quiet = Color(0xFFA9ADB3),
)

val LocalJot2PressColors = staticCompositionLocalOf { LightColors }

/** The widget's badge, in both themes; widget_badge.xml repeats it. The capture dialog's notice wears it too. */
val AlertRed = Color(0xFFE00016)

/** The day number's typeface, and the only font the app bundles. Its licence is in assets/licenses. */
@OptIn(ExperimentalTextApi::class)
val DayNumberFamily = FontFamily(
    Font(
        R.font.schibsted_grotesk,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(900)),
    ),
)

object Jot2PressType {
    /** What the app says. */
    val label = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 17.5.sp, letterSpacing = 0.01.em)

    /** What the user writes. */
    val entry = TextStyle(fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 27.sp)

    /** The user's words in the line about the last entry. */
    val excerpt = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 20.sp)

    val dayNumber = TextStyle(
        fontFamily = DayNumberFamily,
        fontWeight = FontWeight.Black,
        fontSize = 50.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.04).em,
    )

    val send = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 21.sp, letterSpacing = 0.01.em)
}

@Composable
fun Jot2PressTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // Material components are used on the settings and queue screens: every slot they read is set from the six colours.
    val scheme = base.copy(
        primary = colors.app,
        onPrimary = colors.onApp,
        secondary = colors.app,
        onSecondary = colors.onApp,
        tertiary = colors.app,
        onTertiary = colors.onApp,
        primaryContainer = colors.app,
        onPrimaryContainer = colors.onApp,
        secondaryContainer = colors.sheet,
        onSecondaryContainer = colors.app,
        background = colors.sheet,
        onBackground = colors.you,
        surface = colors.sheet,
        onSurface = colors.you,
        surfaceVariant = colors.sheet,
        onSurfaceVariant = colors.quiet,
        surfaceContainerLowest = colors.sheet,
        surfaceContainerLow = colors.sheet,
        surfaceContainer = colors.sheet,
        surfaceContainerHigh = colors.sheet,
        surfaceContainerHighest = colors.sheet,
        surfaceTint = colors.sheet,
        outline = colors.quiet,
        outlineVariant = colors.quiet.copy(alpha = 0.4f),
        error = colors.danger,
        onError = colors.sheet,
    )
    CompositionLocalProvider(LocalJot2PressColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
