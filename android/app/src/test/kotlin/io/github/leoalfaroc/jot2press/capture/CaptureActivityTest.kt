package io.github.leoalfaroc.jot2press.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.TestWorld
import io.github.leoalfaroc.jot2press.data.EntryState
import io.github.leoalfaroc.jot2press.location.Permission
import io.github.leoalfaroc.jot2press.settings.SettingsActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.util.concurrent.CountDownLatch

/** The dialog as the widget opens it, on the real queue. */
@RunWith(AndroidJUnit4::class)
class CaptureActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    /** In the app's own files, which Robolectric empties for each test: the camera is only handed a folder there. */
    private val world by lazy { TestWorld(ApplicationProvider.getApplicationContext<Context>().filesDir) }

    /**
     * FileProvider keeps the folders it may hand out for as long as the JVM lives, and Robolectric moves the app's
     * files for every test: forget the last test's.
     */
    @Before
    fun forgetSharedFolders() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
    }

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
    fun `a rejected sign-in is noticed, and Fix opens settings, keeping the text as the draft`() {
        runBlocking { world.settings.setAuthError(true) }
        open().use { scenario ->
            lateinit var dialog: CaptureActivity
            scenario.onActivity { dialog = it }
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(SIGN_IN_NOTICE)).fetchSemanticsNodes().isNotEmpty() }

            compose.onNode(hasSetTextAction()).performTextInput("Coffee")
            compose.onNodeWithText("Fix").performClick()

            compose.waitUntil(TIMEOUT) { dialog.isFinishing }
            val opened = shadowOf(dialog).nextStartedActivity
            assertEquals(SettingsActivity::class.java.name, opened.component?.className)
            assertFalse(opened.getBooleanExtra(SettingsActivity.OPEN_QUEUE, false))
        }
        compose.waitUntil(TIMEOUT) { entries().isNotEmpty() }
        assertEquals("Coffee", entries().single().text)
    }

    @Test
    fun `entries that could not be sent are noticed while the dialog is open, and Review opens the queue`() {
        open().use { scenario ->
            lateinit var dialog: CaptureActivity
            scenario.onActivity { dialog = it }
            runBlocking {
                val entry = world.write("Coffee")
                world.queue.markFailed(entry.id, "Sorry, you are not allowed to create posts.")
            }
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText("1 entry could not be sent.")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Sorry, you are not allowed to create posts.").assertExists()

            compose.onNodeWithText("Review").performClick()

            compose.waitUntil(TIMEOUT) { dialog.isFinishing }
            assertTrue(shadowOf(dialog).nextStartedActivity.getBooleanExtra(SettingsActivity.OPEN_QUEUE, false))
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
    fun `the dialog counts as on screen from its start to its stop`() {
        assertFalse(world.container.dialogs.onScreen)
        open().use {
            assertTrue(world.container.dialogs.onScreen)
            it.moveToState(Lifecycle.State.CREATED)
            assertFalse(world.container.dialogs.onScreen)
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

    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3, 4, 5, 6, 7, 8)

    private fun draftPhotos() = runBlocking { world.queue.draft()?.let { world.queue.photosOf(it.id) }.orEmpty() }

    @Test
    fun `chosen photos are copied into the draft`() {
        open().use { scenario ->
            compose.onNodeWithContentDescription("Choose photos").performClick()
            lateinit var picker: Intent
            scenario.onActivity { picker = shadowOf(it).nextStartedActivityForResult.intent }
            val uri = Uri.parse("content://media/picker/0/1")
            shadowOf(ApplicationProvider.getApplicationContext<Context>().contentResolver).registerInputStreamSupplier(uri) { jpeg.inputStream() }

            scenario.onActivity { shadowOf(it).receiveResult(picker, Activity.RESULT_OK, Intent().setData(uri)) }

            compose.waitUntil(TIMEOUT) { draftPhotos().size == 1 }
            compose.onNodeWithContentDescription("Photo 1 of 1").assertExists()
        }
    }

    @Test
    fun `Send tapped while a chosen photo is still being copied waits for it`() {
        open().use { scenario ->
            compose.onNode(hasSetTextAction()).performTextInput("Coffee with Ana")
            compose.onNodeWithContentDescription("Choose photos").performClick()
            lateinit var picker: Intent
            scenario.onActivity { picker = shadowOf(it).nextStartedActivityForResult.intent }
            val uri = Uri.parse("content://media/picker/0/1")
            val release = CountDownLatch(1)
            shadowOf(ApplicationProvider.getApplicationContext<Context>().contentResolver).registerInputStreamSupplier(uri) {
                release.await()
                jpeg.inputStream()
            }
            scenario.onActivity { shadowOf(it).receiveResult(picker, Activity.RESULT_OK, Intent().setData(uri)) }

            compose.onNodeWithText("Send").performClick()
            release.countDown()
            val settled = CompletableDeferred<Unit>()
            world.container.captureWork.run { settled.complete(Unit) }
            compose.waitUntil(TIMEOUT) { settled.isCompleted }

            val entry = entries().single()
            assertEquals(1, runBlocking { world.queue.photosOf(entry.id) }.size)
            val kept = runBlocking { world.queue.photoFileNames() }
            assertEquals(kept, world.photoFiles.file("").list().orEmpty().toSet())
        }
    }

    @Test
    fun `a photo taken with the camera joins the draft and is saved to the gallery`() {
        open().use { scenario ->
            compose.onNodeWithContentDescription("Take photo").performClick()
            lateinit var camera: Intent
            scenario.onActivity { camera = shadowOf(it).nextStartedActivityForResult.intent }
            val target = camera.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
            world.photoFiles.file(target.lastPathSegment!!).writeBytes(jpeg)

            scenario.onActivity { shadowOf(it).receiveResult(camera, Activity.RESULT_OK, Intent()) }

            compose.waitUntil(TIMEOUT) { draftPhotos().size == 1 }
            compose.waitUntil(TIMEOUT) { world.galleryCopies == listOf(target.lastPathSegment) }
        }
    }

    @Test
    fun `when the gallery fails, the entry keeps its photo`() {
        world.galleryFailure = IOException("No space")
        open().use { scenario ->
            compose.onNodeWithContentDescription("Take photo").performClick()
            lateinit var camera: Intent
            scenario.onActivity { camera = shadowOf(it).nextStartedActivityForResult.intent }
            val target = camera.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
            world.photoFiles.file(target.lastPathSegment!!).writeBytes(jpeg)

            scenario.onActivity { shadowOf(it).receiveResult(camera, Activity.RESULT_OK, Intent()) }

            compose.waitUntil(TIMEOUT) { draftPhotos().size == 1 }
            assertEquals(listOf(target.lastPathSegment), world.galleryAttempts)
            assertEquals(listOf(target.lastPathSegment), draftPhotos().map { it.file })
            assertTrue(world.photoFiles.file(target.lastPathSegment!!).exists())
        }
    }

    @Test
    fun `a photo taken while Android recreated the dialog still joins the draft`() {
        open().use { scenario ->
            compose.onNodeWithContentDescription("Take photo").performClick()
            lateinit var camera: Intent
            scenario.onActivity { camera = shadowOf(it).nextStartedActivityForResult.intent }
            val target = camera.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
            world.photoFiles.file(target.lastPathSegment!!).writeBytes(jpeg)

            scenario.recreate()
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { shadowOf(it).receiveResult(camera, Activity.RESULT_OK, Intent()) }

            compose.waitUntil(TIMEOUT) { draftPhotos().size == 1 }
        }
    }

    @Test
    fun `with Attach location on the pin is in the dialog, its state is remembered, and leaving stops listening`() {
        runBlocking { world.settings.setAttachLocation(true) }
        world.location.permission = Permission.PRECISE
        open().use { scenario ->
            compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasContentDescription("Attach location")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(TIMEOUT) { world.location.subscribers.value > 0 }

            compose.onNodeWithContentDescription("Attach location").assertIsOn().performClick()
            compose.onNodeWithContentDescription("Attach location").assertIsOff()
            compose.waitUntil(TIMEOUT) { !runBlocking { world.settings.get() }.pinOn }
            compose.waitUntil(TIMEOUT) { world.location.subscribers.value == 0 }

            compose.onNodeWithContentDescription("Attach location").performClick()
            compose.waitUntil(TIMEOUT) { world.location.subscribers.value > 0 }
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(TIMEOUT) { world.location.subscribers.value == 0 }
        }
    }

    @Test
    fun `Send with the pin on stores the position found while the dialog was open, and the search goes on`() {
        runBlocking { world.settings.setAttachLocation(true) }
        world.location.permission = Permission.PRECISE
        open().use { scenario ->
            lateinit var dialog: CaptureActivity
            scenario.onActivity { dialog = it }
            runBlocking { world.position(world.fix(45f, lat = 1.0)) }

            compose.onNode(hasSetTextAction()).performTextInput("Coffee with Ana")
            compose.onNodeWithText("Send").performClick()

            compose.waitUntil(TIMEOUT) { dialog.isFinishing }
            val entry = entries().single()
            assertEquals(EntryState.GRACE, entry.state)
            assertTrue(entry.attachLocation)
            assertEquals(1.0, entry.lat!!, 0.0)
            assertTrue(world.search.running)
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
