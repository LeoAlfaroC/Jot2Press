package io.github.leoalfaroc.journal.settings

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TestWorld
import io.github.leoalfaroc.journal.data.EntryState
import io.github.leoalfaroc.journal.declaredAppName
import io.github.leoalfaroc.journal.queue.EMPTY_MESSAGE
import io.github.leoalfaroc.journal.send.Scheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The settings and queue screens as the launcher opens them, on the real queue. */
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val compose = createEmptyComposeRule()
    private val world by lazy { TestWorld(folder.root) }

    @After
    fun close() = world.close()

    private fun appears(text: String) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `settings is titled with the app's name, and the queue is one step in and one step back`() {
        world
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            appears(declaredAppName())
            appears("Empty")

            compose.onNodeWithText("Queue").performClick()
            appears(EMPTY_MESSAGE)
            compose.onNodeWithText("Test connection").assertDoesNotExist()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            appears("Test connection")

            compose.onNodeWithText("Queue").performClick()
            appears(EMPTY_MESSAGE)
            compose.onNodeWithContentDescription("Back").performClick()
            appears("Test connection")
        }
    }

    @Test
    fun `a failed entry is retried or deleted from the queue screen`() {
        runBlocking {
            world.write("Too long")
            world.write("Not wanted")
            world.entries().forEach { world.queue.markFailed(it.id, "The text is too long.") }
        }
        ActivityScenario.launch(SettingsActivity::class.java).use {
            appears("2 failed")
            compose.onNodeWithText("Queue").performClick()
            appears("Not wanted")

            compose.onAllNodes(hasText("Retry"))[1].performClick()
            compose.waitUntil(TIMEOUT) { runBlocking { world.entries() }.any { it.state == EntryState.PENDING } }
            compose.waitUntil(TIMEOUT) { runBlocking { world.jobs(Scheduler.SEND_WORK) }.isNotEmpty() }
            assertEquals("Too long", runBlocking { world.entries() }.single { it.state == EntryState.PENDING }.text)

            appears("Retry")
            compose.onAllNodes(hasText("Delete"))[0].performClick()
            compose.onNodeWithText("Confirm delete").performClick()
            compose.waitUntil(TIMEOUT) { runBlocking { world.entries() }.size == 1 }
            assertEquals("Too long", runBlocking { world.entries() }.single().text)
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
