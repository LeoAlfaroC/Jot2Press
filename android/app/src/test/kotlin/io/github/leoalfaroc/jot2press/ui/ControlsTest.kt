package io.github.leoalfaroc.jot2press.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlsTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun `a button answers taps, and a disabled one, filled or not, neither answers nor says it can`() {
        compose.setContent {
            Jot2PressTheme {
                Column {
                    Button("Save", { calls += "save" }, filled = true)
                    Button("Send", { calls += "send" }, enabled = false, filled = true)
                    Button("Cancel", { calls += "cancel" }, enabled = false)
                }
            }
        }

        compose.onNodeWithText("Save").assertIsEnabled().assertHeightIsAtLeast(52.dp).performClick()
        compose.onNodeWithText("Send").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Cancel").assertIsNotEnabled().performClick()
        assertEquals(listOf("save"), calls)
    }

    @Test
    fun `an icon button is named by its label, on a 48 dp target`() {
        compose.setContent {
            Jot2PressTheme {
                Column {
                    IconButton(R.drawable.ic_camera, "Take photo", { calls += "camera" })
                    IconButton(R.drawable.ic_photos, "Choose photos", { calls += "photos" }, enabled = false)
                }
            }
        }

        compose.onNodeWithContentDescription("Take photo").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).performClick()
        compose.onNodeWithContentDescription("Choose photos").assertIsNotEnabled().performClick()
        assertEquals(listOf("camera"), calls)
    }

    @Test
    fun `a text action, danger or not, is a button on a 48 dp target`() {
        compose.setContent {
            Jot2PressTheme {
                Column {
                    TextAction("Confirm delete", { calls += "confirm" }, danger = true)
                    TextAction("Keep", { calls += "keep" })
                }
            }
        }

        compose.onNodeWithText("Confirm delete")
            .assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.onNodeWithText("Keep").assertWidthIsAtLeast(48.dp).performClick()
        assertEquals(listOf("confirm", "keep"), calls)
    }
}
