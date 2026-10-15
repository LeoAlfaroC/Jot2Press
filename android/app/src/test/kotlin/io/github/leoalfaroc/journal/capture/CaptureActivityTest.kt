package io.github.leoalfaroc.journal.capture

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TestWorld
import io.github.leoalfaroc.journal.data.EntryState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The dialog as the widget opens it, on the real queue. */
@RunWith(AndroidJUnit4::class)
class CaptureActivityTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()
    private val world by lazy { TestWorld(folder.root) }

    @After
    fun close() = world.close()

    private fun entries() = runBlocking { world.entries() }

    private fun open(): ActivityScenario<CaptureActivity> {
        world
        val scenario = ActivityScenario.launch(CaptureActivity::class.java)
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        return scenario
    }

    @Test
    fun `Send stores the entry and closes the dialog`() {
        open().use { scenario ->
            lateinit var dialog: CaptureActivity
            scenario.onActivity { dialog = it }

            compose.onNode(hasSetTextAction()).performTextInput("Coffee with Ana")
            compose.onNodeWithText("Send").performClick()

            compose.waitUntil(TIMEOUT) { dialog.isFinishing }
            val entry = entries().single()
            assertEquals(EntryState.GRACE, entry.state)
            assertEquals("Coffee with Ana", entry.text)
        }
    }

    @Test
    fun `leaving with text keeps it as the draft, which is there the next time`() {
        open().use {
            compose.onNode(hasSetTextAction()).performTextInput("Walked back along the seafront")
            it.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(TIMEOUT) { entries().isNotEmpty() }
        }
        assertEquals(EntryState.DRAFT, entries().single().state)

        open().use {
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText("Walked back along the seafront")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Discard").performClick()
            compose.onNodeWithText("Confirm discard").performClick()
            compose.waitUntil(TIMEOUT) { entries().isEmpty() }
        }
    }

    @Test
    fun `pausing alone keeps the text as the draft`() {
        open().use {
            compose.onNode(hasSetTextAction()).performTextInput("Walked back along the seafront")
            // Paused but not stopped: Android may wait seconds before it stops a dialog.
            it.moveToState(Lifecycle.State.STARTED)
            compose.waitUntil(TIMEOUT) { entries().isNotEmpty() }
        }
        assertEquals("Walked back along the seafront", entries().single().text)
    }

    @Test
    fun `a dialog opened while the one before is still saving finds what that one held`() {
        val slow = CompletableDeferred<Unit>()
        open().use {
            compose.onNode(hasSetTextAction()).performTextInput("Walked back along the seafront")
            // From here on nothing the dialogs ask of the queue is done, as while a save takes its time.
            world.container.captureWork.run { slow.await() }
        }

        ActivityScenario.launch(CaptureActivity::class.java).use {
            slow.complete(Unit)
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText("Walked back along the seafront")).fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals("Walked back along the seafront", entries().single().text)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
