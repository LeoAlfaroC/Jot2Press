package io.github.leoalfaroc.journal.capture

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.Trouble
import io.github.leoalfaroc.journal.data.DraftPhoto
import io.github.leoalfaroc.journal.ui.CONFIRM_MILLIS
import io.github.leoalfaroc.journal.ui.JournalTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CaptureSheetTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = CaptureActions(
        type = { calls += "type:$it" },
        send = { calls += "send" },
        askDiscard = { calls += "askDiscard" },
        keep = { calls += "keep" },
        confirmDiscard = { calls += "confirmDiscard" },
        editLast = { calls += "editLast" },
        askDeleteLast = { calls += "askDeleteLast" },
        confirmDeleteLast = { calls += "confirmDeleteLast" },
        refresh = { calls += "refresh" },
        fix = { calls += "fix" },
        review = { calls += "review" },
        takePhoto = { calls += "takePhoto" },
        choosePhotos = { calls += "choosePhotos" },
        removePhoto = { calls += "removePhoto:$it" },
    )
    private val blank = CaptureUi(ready = true, day = LocalDate.of(2026, 10, 11))
    private val coffee = LastEntry(id = "id-1", time = "14:32", excerpt = "Coffee with Ana")

    private fun show(ui: CaptureUi, now: Long = 0) {
        compose.setContent { JournalTheme { CaptureSheet(ui, actions, now = { now }) } }
    }

    @Test
    fun `empty, it shows the day and the prompt, and offers neither Send nor Discard`() {
        show(blank)

        compose.onNodeWithContentDescription("Sunday 11 October 2026").assertExists()
        compose.onNodeWithText("What happened?").assertExists()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Discard").assertDoesNotExist()
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun `what is typed goes to the model`() {
        show(blank)

        compose.onNode(hasSetTextAction()).performTextInput("Co")
        compose.onNode(hasSetTextAction()).performTextInput("ffee")
        compose.onNode(hasSetTextAction()).performTextReplacement("")

        assertEquals(listOf("type:Co", "type:Coffee", "type:"), calls)
    }

    @Test
    fun `with text, Send and Discard are offered`() {
        show(blank.copy(text = "Coffee"))

        compose.onNodeWithText("What happened?").assertDoesNotExist()
        compose.onNodeWithText("Coffee").assertExists()
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        compose.onNodeWithText("Discard").performClick()

        assertEquals(listOf("send", "askDiscard"), calls)
    }

    @Test
    fun `after Discard the confirmation takes the day's place, Send is off, and 4 seconds restore the dialog`() {
        compose.mainClock.autoAdvance = false
        show(blank.copy(text = "Coffee", confirmingDiscard = true))
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithContentDescription("Sunday 11 October 2026").assertDoesNotExist()
        compose.onNodeWithText("Discard").assertDoesNotExist()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Confirm discard").performClick()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(listOf("confirmDiscard", "keep"), calls)

        calls.clear()
        compose.mainClock.advanceTimeBy(CONFIRM_MILLIS - 500)
        assertEquals(emptyList<String>(), calls)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("keep"), calls)
    }

    @Test
    fun `the last entry's line offers Edit only while the editor is empty`() {
        show(blank.copy(lastEntry = coffee))

        compose.onNodeWithText("14:32  Coffee with Ana").assertExists()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("editLast", "askDeleteLast"), calls)
    }

    @Test
    fun `with text in the editor the last entry can be deleted but not edited`() {
        show(blank.copy(text = "Something new", lastEntry = coffee))

        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertExists()
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test
    fun `after Delete the confirmation replaces the line, and 4 seconds restore it`() {
        compose.mainClock.autoAdvance = false
        show(blank.copy(text = "Something new", lastEntry = coffee.copy(confirmingDelete = true)))
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("14:32  Coffee with Ana").assertDoesNotExist()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.onNodeWithText("Confirm delete").performClick()
        assertEquals(listOf("confirmDeleteLast"), calls)

        calls.clear()
        compose.mainClock.advanceTimeBy(CONFIRM_MILLIS + 500)
        assertEquals(listOf("keep"), calls)
    }

    @Test
    fun `it asks the model to look again when the next change is due`() {
        compose.mainClock.autoAdvance = false
        show(blank.copy(lastEntry = coffee, nextChangeAt = 1_040_000), now = 1_000_000)

        compose.mainClock.advanceTimeBy(39_000)
        assertEquals(emptyList<String>(), calls)
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun `while nothing needs fixing there is no notice`() {
        show(blank)
        compose.onNodeWithText("Fix").assertDoesNotExist()
        compose.onNodeWithText("Review").assertDoesNotExist()
    }

    @Test
    fun `a rejected sign-in is noticed on top, and Fix leads to settings`() {
        show(blank.copy(trouble = Trouble.SignInRejected, lastEntry = coffee))

        compose.onNodeWithText(SIGN_IN_NOTICE).assertExists()
        compose.onNodeWithText("!").assertExists()
        val notice = compose.onNodeWithText(SIGN_IN_NOTICE).getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText("Coffee with Ana", substring = true).getUnclippedBoundsInRoot()
        assertTrue(notice.bottom <= last.top)
        compose.onNodeWithText("Fix").performClick()
        assertEquals(listOf("fix"), calls)
    }

    @Test
    fun `one entry that could not be sent is noticed with what the site answered, and Review leads to the queue`() {
        show(blank.copy(trouble = Trouble.NotSent(1, "Sorry, you are not allowed to create posts.")))

        compose.onNodeWithText("1 entry could not be sent.").assertExists()
        compose.onNodeWithText("Sorry, you are not allowed to create posts.").assertExists()
        compose.onNodeWithText("Review").performClick()
        assertEquals(listOf("review"), calls)
    }

    @Test
    fun `several are counted`() {
        show(blank.copy(trouble = Trouble.NotSent(3, null)))
        compose.onNodeWithText("3 entries could not be sent.").assertExists()
        compose.onNodeWithText("3").assertExists()
    }

    @Test
    fun `the notice does not get in the way of writing`() {
        show(blank.copy(trouble = Trouble.SignInRejected))
        compose.onNode(hasSetTextAction()).performTextInput("Coffee")
        assertEquals(listOf("type:Coffee"), calls)
    }

    private fun photos(count: Int) = List(count) { DraftPhoto("photo-${it + 1}", "photo-${it + 1}.jpg") }

    @Test
    fun `the camera and photos buttons sit beside Send and say what they do`() {
        show(blank)

        compose.onNodeWithContentDescription("Take photo").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Choose photos").assertIsEnabled().performClick()

        assertEquals(listOf("takePhoto", "choosePhotos"), calls)
        val send = compose.onNodeWithText("Send").getUnclippedBoundsInRoot()
        val photos = compose.onNodeWithContentDescription("Choose photos").getUnclippedBoundsInRoot()
        assertTrue(photos.right <= send.left)
    }

    @Test
    fun `photos show as thumbnails, each one removable, and they alone allow Send and Discard`() {
        show(blank.copy(photos = photos(3)))

        compose.onNodeWithContentDescription("Photo 2 of 3").assertExists()
        compose.onNodeWithContentDescription("Remove photo 2").performClick()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.onNodeWithText("Discard").assertExists()

        assertEquals(listOf("removePhoto:photo-2"), calls)
    }

    @Test
    fun `at 10 photos both buttons stop working`() {
        show(blank.copy(photos = photos(10)))

        compose.onNodeWithContentDescription("Take photo").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Choose photos").assertIsNotEnabled()
    }

    @Test
    fun `a remove mark answers to a touch area of 48 dp`() {
        show(blank.copy(photos = photos(1)))

        val mark = compose.onNodeWithContentDescription("Remove photo 1").getUnclippedBoundsInRoot()
        assertEquals(48f, (mark.right - mark.left).value, 0.5f)
        assertEquals(48f, (mark.bottom - mark.top).value, 0.5f)
    }

    @Test
    fun `a remove mark in the strip past the sheet's right padding still answers`() {
        show(blank.copy(photos = photos(5)))

        val rootWidth = compose.onRoot().getUnclippedBoundsInRoot().right
        val mark = compose.onNodeWithContentDescription("Remove photo 4").getUnclippedBoundsInRoot()
        val x = rootWidth - 10.dp
        assertTrue(mark.left < x && x < mark.right && x < rootWidth)
        val y = (mark.top + mark.bottom) / 2

        compose.onNodeWithContentDescription("Remove photo 4").performTouchInput {
            click(Offset((x - mark.left).toPx(), (y - mark.top).toPx()))
        }

        assertEquals(listOf("removePhoto:photo-4"), calls)
    }
}
