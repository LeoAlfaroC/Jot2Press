package io.github.leoalfaroc.jot2press.queue

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.ui.CONFIRM_MILLIS
import io.github.leoalfaroc.jot2press.ui.FakeHaptics
import io.github.leoalfaroc.jot2press.ui.Jot2PressTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueScreenTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val waiting = QueueRow("id-1", "14:20", "Walked back", "Waiting")
    private val failed = QueueRow("id-2", "13:05", "Too long", "Failed", detail = "The text is too long.", canRetry = true)

    private val haptics = FakeHaptics()

    private fun show(ui: QueueUi) {
        compose.setContent {
            Jot2PressTheme {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    QueueScreen(ui, onRetry = { calls += "retry:$it" }, onDelete = { calls += "delete:$it" }, onBack = { calls += "back" })
                }
            }
        }
    }

    @Test
    fun `with nothing in the queue it says so in one line`() {
        show(QueueUi())

        compose.onNodeWithText("Queue").assertExists()
        compose.onNodeWithText(EMPTY_MESSAGE).assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("back"), calls)
    }

    @Test
    fun `it shows the messages and each entry with its time, state, text and error`() {
        show(QueueUi(messages = listOf(AUTH_MESSAGE), rows = listOf(waiting, failed)))

        compose.onNodeWithText(AUTH_MESSAGE).assertExists()
        compose.onNodeWithText(EMPTY_MESSAGE).assertDoesNotExist()
        compose.onNodeWithText("14:20  Waiting").assertExists()
        compose.onNodeWithText("Walked back").assertExists()
        compose.onNodeWithText("13:05  Failed").assertExists()
        compose.onNodeWithText("The text is too long.").assertExists()
    }

    @Test
    fun `only a failed entry offers Retry`() {
        show(QueueUi(rows = listOf(waiting)))
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `Retry asks for the failed entry to be sent again`() {
        show(QueueUi(rows = listOf(failed)))

        compose.onNodeWithText("Retry").performClick()

        assertEquals(listOf("retry:id-2"), calls)
    }

    @Test
    fun `Delete asks first, and Keep or 4 seconds take the question away`() {
        compose.mainClock.autoAdvance = false
        show(QueueUi(rows = listOf(failed)))
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Keep").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Confirm delete").assertDoesNotExist()

        compose.onNodeWithText("Delete").performClick()
        compose.mainClock.advanceTimeBy(CONFIRM_MILLIS + 500)
        compose.onNodeWithText("Confirm delete").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertExists()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `Confirm delete deletes that entry`() {
        show(QueueUi(rows = listOf(waiting, failed)))

        compose.onAllNodesWithText("Delete")[1].performClick()
        compose.onNodeWithText("Confirm delete").performClick()

        assertEquals(listOf("delete:id-2"), calls)
        compose.onNodeWithText("Confirm delete").assertDoesNotExist()
    }

    @Test
    fun `Confirm delete confirms with one haptic`() {
        show(QueueUi(rows = listOf(failed)))

        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Confirm delete").performClick()

        assertEquals(listOf(HapticFeedbackType.Confirm), haptics.performed)
    }

    @Test
    fun `Retry, Delete and Keep give no haptic`() {
        show(QueueUi(rows = listOf(failed)))

        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Keep").performClick()

        assertEquals(emptyList<HapticFeedbackType>(), haptics.performed)
    }
}
