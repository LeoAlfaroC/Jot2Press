package io.github.leoalfaroc.jot2press.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.leoalfaroc.jot2press.R

/**
 * Two voices. `text` is what the person writes. `ink` is everything the app says or does: the day, actions, icons, Send, the caret.
 * The scheme is fixed: it does not derive from the wallpaper.
 */
@Immutable
data class Jot2PressColors(
    /** The wordmark and the sheet's edge differ between the two schemes by more than a colour. */
    val dark: Boolean,
    /** Every surface: the sheet, settings, the queue. */
    val paper: Color,
    /** A thumbnail while it decodes. Never under ink text. */
    val wash: Color,
    /** Hairlines between rows. */
    val line: Color,
    val text: Color,
    /** Secondary labels, field borders, controls that cannot be used. */
    val quiet: Color,
    val ink: Color,
    /** The press ripple of ink controls. */
    val inkRipple: Color,
    val onInk: Color,
    /** On `Confirm discard` and `Confirm delete`, and on what the person has to fix. */
    val danger: Color,
)

val LightColors = Jot2PressColors(
    dark = false,
    paper = Color(0xFFFFFFFF),
    wash = Color(0xFFEEF0F3),
    line = Color(0xFFD9DCE1),
    text = Color(0xFF16171B),
    quiet = Color(0xFF646873),
    ink = Color(0xFF0066FF),
    inkRipple = Color(0x1F0066FF),
    onInk = Color(0xFFFFFFFF),
    danger = Color(0xFFD7001A),
)

val DarkColors = Jot2PressColors(
    dark = true,
    paper = Color(0xFF101218),
    wash = Color(0xFF1C1F27),
    line = Color(0xFF2E323C),
    text = Color(0xFFF2F2EF),
    quiet = Color(0xFF9EA2AC),
    ink = Color(0xFF7AB0FF),
    inkRipple = Color(0x2E7AB0FF),
    onInk = Color(0xFF101218),
    danger = Color(0xFFFF7B7B),
)

val LocalJot2PressColors = staticCompositionLocalOf { LightColors }

/** The alert mark, in both themes; widget_badge.xml repeats it. */
val Alert = Color(0xFFE00016)
val OnAlert = Color(0xFFFFFFFF)

/** Selected text, and the band behind the 2 of the wordmark. */
val Highlighter = Color(0xFFFFEB00)

/** The day number and headings: static instances of Shantell Sans. The licences of both families are in assets/licenses. */
val Display = FontFamily(
    Font(R.font.shantell_sans_bold, FontWeight.Bold),
    Font(R.font.shantell_sans_extrabold, FontWeight.ExtraBold),
)

/** Everything else: Atkinson Hyperlegible. */
val Body = FontFamily(
    Font(R.font.atkinson_hyperlegible_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_bold, FontWeight.Bold),
)

object Jot2PressType {
    val dayNumber = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 54.sp, lineHeight = 54.sp, letterSpacing = (-0.03).em)

    /** Screen and section headings. */
    val heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em)

    /** What the person writes. */
    val entry = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 27.sp)

    /** Setting names, saved values, excerpts, queue rows. */
    val body = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)

    /** Text actions, field labels, weekday and month, times, notice headlines. */
    val label = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp)

    /** Help and status lines, a notice's reason, a queue row's state. */
    val note = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)

    val button = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp)

    /** The count or `!` in the alert mark. */
    val badge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 11.sp)
}

/** Every Material style in [family], so that nothing falls back to Roboto. */
private fun Typography.inFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

private val BodyTypography = Typography().inFamily(Body)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Jot2PressTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // The category menu is Material: every slot it could read is set from the tokens.
    val scheme = base.copy(
        primary = colors.ink,
        onPrimary = colors.onInk,
        secondary = colors.ink,
        onSecondary = colors.onInk,
        tertiary = colors.ink,
        onTertiary = colors.onInk,
        primaryContainer = colors.ink,
        onPrimaryContainer = colors.onInk,
        secondaryContainer = colors.ink,
        onSecondaryContainer = colors.onInk,
        tertiaryContainer = colors.ink,
        onTertiaryContainer = colors.onInk,
        background = colors.paper,
        onBackground = colors.text,
        surface = colors.paper,
        onSurface = colors.text,
        surfaceVariant = colors.paper,
        onSurfaceVariant = colors.quiet,
        surfaceContainerLowest = colors.paper,
        surfaceContainerLow = colors.paper,
        surfaceContainer = colors.paper,
        surfaceContainerHigh = colors.paper,
        surfaceContainerHighest = colors.paper,
        surfaceTint = colors.paper,
        outline = colors.quiet,
        outlineVariant = colors.line,
        error = colors.danger,
        onError = colors.paper,
    )
    val pressed = colors.inkRipple.alpha
    MaterialTheme(colorScheme = scheme, typography = BodyTypography) {
        // Inside MaterialTheme, which provides selection colours and ripples of its own.
        CompositionLocalProvider(
            LocalJot2PressColors provides colors,
            // Compose cannot recolour selected text: light text on full yellow would be unreadable in the dark theme.
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.ink,
                backgroundColor = if (dark) Highlighter.copy(alpha = 0.4f) else Highlighter,
            ),
            LocalRippleConfiguration provides RippleConfiguration(
                color = colors.ink,
                rippleAlpha = RippleAlpha(draggedAlpha = pressed, focusedAlpha = pressed, hoveredAlpha = pressed, pressedAlpha = pressed),
            ),
            content = content,
        )
    }
}
