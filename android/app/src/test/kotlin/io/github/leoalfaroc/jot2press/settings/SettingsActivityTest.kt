package io.github.leoalfaroc.jot2press.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.TestWorld
import io.github.leoalfaroc.jot2press.data.EntryState
import io.github.leoalfaroc.jot2press.declaredAppName
import io.github.leoalfaroc.jot2press.location.Permission
import io.github.leoalfaroc.jot2press.queue.EMPTY_MESSAGE
import io.github.leoalfaroc.jot2press.send.Scheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivity

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
    fun `opened for the queue, it starts on the queue, and back returns to settings`() {
        world
        ActivityScenario.launch<SettingsActivity>(SettingsActivity.queueIntent(world.context)).use { scenario ->
            appears(EMPTY_MESSAGE)
            compose.onNodeWithText("Test connection").assertDoesNotExist()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            appears("Test connection")
        }
    }

    @Test
    fun `two quick edits of the grace window are stored in the order they were typed`() {
        val actions = SettingsViewModel(world.container).actions(openQueue = {}, askLocation = {}, openAppSettings = {})

        actions.editGrace("1")
        actions.editGrace("12")

        val stored = runBlocking {
            withTimeout(TIMEOUT) {
                while (world.settings.get().graceSeconds != 12) delay(10)
                // Nothing later may put the first edit back.
                delay(100)
                world.settings.get().graceSeconds
            }
        }
        assertEquals(12, stored)
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

    private fun lastPermissionRequest(scenario: ActivityScenario<SettingsActivity>): ShadowActivity.PermissionsRequest {
        lateinit var request: ShadowActivity.PermissionsRequest
        scenario.onActivity { request = shadowOf(it).lastRequestedPermission }
        return request
    }

    /** Answers Android's prompt with `result` for every permission it asked for. */
    @Suppress("DEPRECATION")
    private fun answer(scenario: ActivityScenario<SettingsActivity>, request: ShadowActivity.PermissionsRequest, result: Int) {
        scenario.onActivity {
            it.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, IntArray(request.requestedPermissions.size) { result })
        }
    }

    private fun stored() = runBlocking { world.settings.get() }

    @Test
    fun `turning Attach location on asks for the location permission, and a grant turns it and the pin on`() {
        world
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            appears("Attach location")
            compose.onNodeWithText("Attach location").performScrollTo().assertIsOff().performClick()

            val request = lastPermissionRequest(scenario)
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), request.requestedPermissions.toSet())
            world.location.permission = Permission.PRECISE
            answer(scenario, request, PackageManager.PERMISSION_GRANTED)

            compose.waitUntil(TIMEOUT) { stored().attachLocation }
            assertTrue(stored().pinOn)
            compose.onNodeWithText("Attach location").assertIsOn()
        }
    }

    @Test
    fun `a refusal Android will not ask again leaves it off, with a line whose Open shows the app in Android settings`() {
        world
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            appears("Attach location")
            compose.onNodeWithText("Attach location").performScrollTo().performClick()

            answer(scenario, lastPermissionRequest(scenario), PackageManager.PERMISSION_DENIED)

            appears(LOCATION_BLOCKED_LINE)
            compose.onNodeWithText("Attach location").assertIsOff()
            assertFalse(stored().attachLocation)
            compose.onNodeWithText("Open").performScrollTo().performClick()
            lateinit var opened: Intent
            scenario.onActivity { opened = shadowOf(it).nextStartedActivity }
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
            assertEquals("package:io.github.leoalfaroc.jot2press", opened.dataString)
        }
    }

    @Test
    fun `returning to settings looks again at the location permission`() {
        runBlocking { world.settings.setAttachLocation(true) }
        world.location.permission = Permission.PRECISE
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            appears("Attach location")
            compose.onNodeWithText("Attach location").performScrollTo().assertIsOn()

            world.location.permission = Permission.NONE
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            compose.onNodeWithText("Attach location").assertIsOff()
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
