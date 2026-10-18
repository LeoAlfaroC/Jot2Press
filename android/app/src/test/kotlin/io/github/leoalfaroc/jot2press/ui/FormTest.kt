package io.github.leoalfaroc.jot2press.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FormTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val statuses = listOf("Private", "Draft", "Public")

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { Jot2PressTheme { Box(Modifier.width(320.dp)) { content() } } }
    }

    @Test
    fun `a field shows its label and help, and what is typed goes to the model`() {
        show { Field("Site URL", "", 1, { calls += it }, help = "Starts with https://") }

        compose.onNodeWithText("Starts with https://").assertExists()
        compose.onNodeWithText("Site URL").performTextReplacement("example.com")

        assertEquals(listOf("example.com"), calls)
    }

    @Test
    fun `an error takes the help line's place`() {
        show { Field("Grace window, in seconds", "abc", 1, {}, help = "A hint.", error = "Enter a number.") }

        compose.onNodeWithText("Enter a number.").assertExists()
        compose.onNodeWithText("A hint.").assertDoesNotExist()
    }

    @Test
    fun `a hidden field is a password field, and a disabled one says so`() {
        show { Field("Application password", "abcd efgh", 1, {}, hidden = true, enabled = false) }

        compose.onNodeWithText("Application password")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assertIsNotEnabled()
        // What TalkBack reads is masked. Compose keeps the typed value in InputText as well, for autofill.
        compose.onNodeWithText("Application password").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("•".repeat(9))))
    }

    @Test
    fun `a switch row toggles from anywhere on the row and reports its state`() {
        show {
            var on by remember { mutableStateOf(false) }
            SwitchRow("Upload on Wi-Fi only", on, { on = it })
        }
        val row = compose.onNodeWithText("Upload on Wi-Fi only")

        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
        row.performTouchInput { click(centerLeft + Offset(4f, 0f)) }
        row.assertIsOn()
        row.performTouchInput { click(centerRight - Offset(4f, 0f)) }
        row.assertIsOff()
    }

    @Test
    fun `choice pills say which one is selected, and a tap selects another`() {
        show {
            var chosen by remember { mutableStateOf("Private") }
            Choice("Post status", statuses, chosen, { it }, { chosen = it })
        }

        compose.onNodeWithText("Private").assertIsSelected()
        compose.onNodeWithText("Draft").assertIsNotSelected().performClick()
        compose.onNodeWithText("Draft").assertIsSelected()
        compose.onNodeWithText("Private").assertIsNotSelected()
    }

    @Test
    fun `at twice the text size the pills wrap instead of running off the screen`() {
        compose.setContent {
            Jot2PressTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Box(Modifier.width(320.dp)) { Choice("Post status", statuses, "Private", { it }, {}) }
                }
            }
        }

        for (status in statuses) {
            assertTrue(status, compose.onNodeWithText(status).getUnclippedBoundsInRoot().right <= 320.dp)
        }
    }

    @Test
    fun `a link row keeps to one line however long its detail`() {
        show { LinkRow("Category", "A category with a very long name ".repeat(8), { calls += "open" }) }

        compose.onNodeWithText("Category").assertIsDisplayed().assertHeightIsEqualTo(56.dp).performClick()

        assertEquals(listOf("open"), calls)
    }

    @Test
    fun `a disabled link row does not answer`() {
        show { LinkRow("Category", "", { calls += "open" }, enabled = false) }

        compose.onNodeWithText("Category").assertIsNotEnabled().performClick()

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a heading is announced as a heading, with its action beside it`() {
        show { Heading("Connection") { TextAction("Edit", { calls += "edit" }) } }

        compose.onNodeWithText("Connection").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Edit").performClick()

        assertEquals(listOf("edit"), calls)
    }
}
