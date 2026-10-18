package io.github.leoalfaroc.jot2press.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @get:Rule val compose = createComposeRule()

    /** What a screen drawn inside the theme reads. */
    private class Seen {
        lateinit var colors: Jot2PressColors
        lateinit var scheme: ColorScheme
        lateinit var style: TextStyle
        lateinit var label: TextStyle
        lateinit var selection: TextSelectionColors
    }

    private fun seen(dark: Boolean): Seen {
        val seen = Seen()
        compose.setContent {
            Jot2PressTheme(dark = dark) {
                seen.colors = LocalJot2PressColors.current
                seen.scheme = MaterialTheme.colorScheme
                seen.style = LocalTextStyle.current
                seen.label = MaterialTheme.typography.labelLarge
                seen.selection = LocalTextSelectionColors.current
            }
        }
        compose.waitForIdle()
        return seen
    }

    @Test
    fun `light - ink on paper, and Material reads the same tokens`() {
        val seen = seen(dark = false)

        assertEquals(Color(0xFF0066FF), seen.colors.ink)
        assertEquals(Color(0xFFFFFFFF), seen.colors.paper)
        assertEquals(Color(0xFF16171B), seen.colors.text)
        assertEquals(Color(0xFF646873), seen.colors.quiet)
        assertEquals(Color(0xFFD7001A), seen.colors.danger)
        assertEquals(Color(0xFFD9DCE1), seen.colors.line)
        assertEquals(Color(0xFFEEF0F3), seen.colors.wash)
        assertEquals(Color(0xFFFFFFFF), seen.colors.onInk)
        assertEquals(seen.colors.ink, seen.scheme.primary)
        assertEquals(seen.colors.onInk, seen.scheme.onPrimary)
        assertEquals(seen.colors.paper, seen.scheme.surfaceContainer)
        assertEquals(seen.colors.text, seen.scheme.onSurface)
        assertEquals(seen.colors.line, seen.scheme.outlineVariant)
        assertEquals(seen.colors.danger, seen.scheme.error)
        assertEquals(seen.colors.ink, seen.selection.handleColor)
        assertEquals(Highlighter, seen.selection.backgroundColor)
    }

    @Test
    fun `dark - the lighter ink on the dark paper, and the selection at 40 percent`() {
        val seen = seen(dark = true)

        assertEquals(Color(0xFF7AB0FF), seen.colors.ink)
        assertEquals(Color(0xFF101218), seen.colors.paper)
        assertEquals(Color(0xFFF2F2EF), seen.colors.text)
        assertEquals(Color(0xFF9EA2AC), seen.colors.quiet)
        assertEquals(Color(0xFFFF7B7B), seen.colors.danger)
        assertEquals(Color(0xFF2E323C), seen.colors.line)
        assertEquals(Color(0xFF1C1F27), seen.colors.wash)
        assertEquals(Color(0xFF101218), seen.colors.onInk)
        assertEquals(seen.colors.ink, seen.scheme.primary)
        assertEquals(seen.colors.paper, seen.scheme.background)
        assertEquals(Highlighter.copy(alpha = 0.4f), seen.selection.backgroundColor)
    }

    @Test
    fun `text that names no family is set in Atkinson Hyperlegible`() {
        val seen = seen(dark = false)

        assertEquals(Body, seen.style.fontFamily)
        assertEquals(Body, seen.label.fontFamily)
    }
}
