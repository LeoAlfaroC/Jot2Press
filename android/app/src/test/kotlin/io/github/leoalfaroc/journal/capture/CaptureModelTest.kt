package io.github.leoalfaroc.journal.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import io.github.leoalfaroc.journal.TestWorld
import io.github.leoalfaroc.journal.Trouble
import io.github.leoalfaroc.journal.data.DraftPhoto
import io.github.leoalfaroc.journal.data.EntryState
import io.github.leoalfaroc.journal.data.MAX_PHOTOS
import io.github.leoalfaroc.journal.send.Scheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CaptureModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val world by lazy { TestWorld(folder.root) }
    private val sunday = LocalDate.of(2026, 10, 11)

    @After
    fun close() = world.close()

    /** The dialog as it is each time it opens. */
    private suspend fun opened() = CaptureModel(world.queue, world.settings, world.scheduler, world.clock).also { it.open() }

    @Test
    fun `it opens empty, on the current day, with nothing to send or discard`() = runTest {
        val model = CaptureModel(world.queue, world.settings, world.scheduler, world.clock)
        assertFalse(model.ui.value.ready)

        model.open()

        val ui = model.ui.value
        assertTrue(ui.ready)
        assertEquals("", ui.text)
        assertEquals(sunday, ui.day)
        assertFalse(ui.hasContent)
        assertFalse(ui.canSend)
        assertNull(ui.lastEntry)

        model.type("   ")
        assertFalse(model.ui.value.canSend)
        assertEquals(1, model.ui.value.revision)
        model.send()
        assertFalse(model.ui.value.closed)
        assertTrue(world.entries().isEmpty())
    }

    @Test
    fun `closing with text keeps it as the draft, which is there the next time`() = runTest {
        val first = opened()
        first.type("Walked back along the seafront")
        first.saveDraft()

        val draft = world.entries().single()
        assertEquals(EntryState.DRAFT, draft.state)
        assertNull(draft.writtenAt)

        val second = opened()
        assertEquals("Walked back along the seafront", second.ui.value.text)
        assertTrue(second.ui.value.canSend)

        second.type("")
        second.saveDraft()
        assertTrue(world.entries().isEmpty())
    }

    @Test
    fun `Send stores the entry for the grace window, closes, and schedules the wake-up`() = runTest {
        val model = opened()
        model.type("Coffee with Ana")

        model.send()

        assertTrue(model.ui.value.closed)
        val entry = world.entries().single()
        assertEquals(EntryState.GRACE, entry.state)
        assertEquals("Coffee with Ana", entry.text)
        assertEquals("2026-10-11T14:32:05-05:00", entry.writtenAt)
        assertEquals(world.clock.millis() + 60_000, entry.graceEndsAt)
        assertEquals(60_000L, world.jobs(Scheduler.graceWork(entry.id)).single().initialDelayMillis)

        // Whatever arrives after the dialog closed changes nothing.
        model.type("Late")
        model.saveDraft()
        model.send()
        assertEquals("Coffee with Ana", world.entries().single().text)
    }

    @Test
    fun `with a grace window of 0 the entry is ready at once and the sender is scheduled`() = runTest {
        world.settings.setGraceSeconds(0)
        val model = opened()
        model.type("Coffee")

        model.send()

        assertEquals(EntryState.PENDING, world.entries().single().state)
        assertEquals(NetworkType.CONNECTED, world.jobs(Scheduler.SEND_WORK).single().constraints.requiredNetworkType)
        assertNull(opened().ui.value.lastEntry)
    }

    @Test
    fun `Discard asks first, and Send is off while it asks`() = runTest {
        val model = opened()
        model.askDiscard()
        assertFalse(model.ui.value.confirmingDiscard)

        model.type("Coffee")
        model.saveDraft()
        model.askDiscard()
        assertTrue(model.ui.value.confirmingDiscard)
        assertFalse(model.ui.value.canSend)
        model.send()
        assertFalse(model.ui.value.closed)

        model.keep()
        assertFalse(model.ui.value.confirmingDiscard)
        assertTrue(model.ui.value.canSend)
        model.confirmDiscard()
        assertFalse(model.ui.value.closed)
        assertEquals("Coffee", world.entries().single().text)

        model.askDiscard()
        model.confirmDiscard()
        assertTrue(model.ui.value.closed)
        assertTrue(world.entries().isEmpty())
        model.saveDraft()
        assertTrue(world.entries().isEmpty())
    }

    @Test
    fun `the day changes at midnight`() = runTest {
        val model = opened()
        val untilMidnight = Duration.ofHours(9).plusMinutes(27).plusSeconds(55)
        assertEquals(world.clock.millis() + untilMidnight.toMillis(), model.ui.value.nextChangeAt)

        world.clock.advance(untilMidnight)
        model.refresh()

        assertEquals(sunday.plusDays(1), model.ui.value.day)
        assertEquals(world.clock.millis() + Duration.ofDays(1).toMillis(), model.ui.value.nextChangeAt)
    }

    @Test
    fun `the last entry is shown only while it is in its grace window`() = runTest {
        opened().apply { type("Coffee with\nAna   at the corner place"); send() }
        val sentAt = world.clock.millis()
        world.clock.advance(Duration.ofSeconds(20))

        val model = opened()
        val last = model.ui.value.lastEntry!!
        assertEquals("14:32", last.time)
        assertEquals("Coffee with Ana at the corner place", last.excerpt)
        assertFalse(last.confirmingDelete)
        assertEquals(sentAt + 60_000, model.ui.value.nextChangeAt)
        assertTrue(model.ui.value.canEditLast)

        model.type("Something new")
        assertFalse(model.ui.value.canEditLast)
        model.editLast()
        assertEquals("Something new", model.ui.value.text)
        assertEquals(EntryState.GRACE, world.entries().single().state)

        world.clock.advance(Duration.ofSeconds(40))
        model.refresh()
        assertNull(model.ui.value.lastEntry)
        assertEquals("Something new", model.ui.value.text)
    }

    @Test
    fun `only the most recent entry is offered, and not when it is no longer in its grace window`() = runTest {
        world.write("Older", graceSeconds = 600)
        world.write("Newer", graceSeconds = 30)

        assertNull(opened().ui.value.lastEntry)
    }

    @Test
    fun `Edit takes the last entry back, keeps its day and its time, and starts a new grace window`() = runTest {
        world.clock.advance(Duration.ofHours(9).plusMinutes(27).plusSeconds(25))
        opened().apply { type("Late coffee"); send() }
        val written = world.entries().single().writtenAt
        assertEquals("2026-10-11T23:59:30-05:00", written)
        world.clock.advance(Duration.ofSeconds(40))

        val model = opened()
        assertEquals(sunday.plusDays(1), model.ui.value.day)
        val revision = model.ui.value.revision
        model.editLast()

        assertEquals("Late coffee", model.ui.value.text)
        assertEquals(revision + 1, model.ui.value.revision)
        assertEquals(sunday, model.ui.value.day)
        assertNull(model.ui.value.lastEntry)
        assertEquals(EntryState.DRAFT, world.entries().single().state)

        // Closed and opened again, it is still that entry and that day.
        val again = opened()
        assertEquals(sunday, again.ui.value.day)
        again.type("Late coffee, decaf")
        again.send()

        val entry = world.entries().single()
        assertEquals(EntryState.GRACE, entry.state)
        assertEquals("Late coffee, decaf", entry.text)
        assertEquals(written, entry.writtenAt)
        assertEquals(world.clock.millis() + 60_000, entry.graceEndsAt)
    }

    @Test
    fun `Edit does nothing once the grace window has ended`() = runTest {
        opened().apply { type("Coffee"); send() }
        val model = opened()
        world.clock.advance(Duration.ofSeconds(61))

        model.editLast()

        assertEquals("", model.ui.value.text)
        assertNull(model.ui.value.lastEntry)
        assertEquals(EntryState.GRACE, world.entries().single().state)
    }

    @Test
    fun `Edit works right after the editor was emptied, before the draft was saved`() = runTest {
        opened().apply { type("Coffee"); send() }
        val model = opened()
        model.type("Never mind")
        model.saveDraft()
        model.type("")

        model.editLast()

        assertEquals("Coffee", model.ui.value.text)
        assertEquals(EntryState.DRAFT, world.entries().single().state)
    }

    @Test
    fun `what is typed while the last entry is being taken back stays, after its text`() = runTest {
        opened().apply { type("Coffee"); send() }
        val model = opened()
        // The queue reads the clock in the middle of taking the entry back: that is when this is typed.
        world.clock.whenRead = {
            world.clock.whenRead = {}
            model.type(" with Ana")
        }

        model.editLast()

        assertEquals("Coffee with Ana", model.ui.value.text)
        assertEquals(sunday, model.ui.value.entryDay)
        model.saveDraft()
        val entry = world.entries().single()
        assertEquals(EntryState.DRAFT, entry.state)
        assertEquals("Coffee with Ana", entry.text)
        assertEquals("2026-10-11T14:32:05-05:00", entry.writtenAt)
    }

    @Test
    fun `reopening a taken-back entry and saving it unchanged keeps its id and its time`() = runTest {
        opened().apply { type("Tea"); send() }
        val sent = world.entries().single()
        world.clock.advance(Duration.ofSeconds(10))
        val first = opened()
        first.editLast()
        first.saveDraft()

        val again = opened()
        assertEquals("Tea", again.ui.value.text)
        again.saveDraft()

        val entry = world.entries().single()
        assertEquals(EntryState.DRAFT, entry.state)
        assertEquals(sent.id, entry.id)
        assertEquals(sent.writtenAt, entry.writtenAt)
    }

    @Test
    fun `emptying the editor lets go of an entry taken back, and what is typed next is a new entry`() = runTest {
        opened().apply { type("Tea"); send() }
        val tea = world.entries().single()
        world.clock.advance(Duration.ofSeconds(10))
        val model = opened()
        model.editLast()
        assertEquals("Tea", model.ui.value.text)

        world.clock.advance(Duration.ofDays(1))
        model.refresh()
        assertEquals(sunday, model.ui.value.day)
        model.type("")
        assertEquals(sunday.plusDays(1), model.ui.value.day)
        model.type("Water")
        model.send()

        val water = world.entries().single()
        assertEquals("Water", water.text)
        assertTrue(water.id != tea.id)
        assertEquals("2026-10-12T14:32:15-05:00", water.writtenAt)
    }

    @Test
    fun `Delete on the last entry asks first, then removes it`() = runTest {
        opened().apply { type("Coffee"); send() }
        val model = opened()
        model.type("Draft text")

        model.confirmDeleteLast()
        assertEquals(1, world.entries().size)

        model.askDeleteLast()
        assertTrue(model.ui.value.lastEntry!!.confirmingDelete)
        assertTrue(model.ui.value.canSend)
        model.keep()
        assertFalse(model.ui.value.lastEntry!!.confirmingDelete)

        model.askDiscard()
        model.askDeleteLast()
        assertFalse(model.ui.value.confirmingDiscard)
        model.askDiscard()
        assertFalse(model.ui.value.lastEntry!!.confirmingDelete)
        model.askDeleteLast()

        model.confirmDeleteLast()
        assertNull(model.ui.value.lastEntry)
        assertEquals("Draft text", model.ui.value.text)
        assertTrue(world.entries().isEmpty())
    }

    @Test
    fun `what needs fixing follows the queue and the settings`() = runTest {
        val model = opened()
        assertNull(model.trouble.first())

        val entry = world.write("Coffee")
        world.queue.markFailed(entry.id, "Sorry, you are not allowed to create posts.")
        assertEquals(Trouble.NotSent(1, "Sorry, you are not allowed to create posts."), model.trouble.first())

        world.settings.setAuthError(true)
        assertEquals(Trouble.SignInRejected, model.trouble.first())
    }

    private var photoIds = 0

    private fun copy(): DraftPhoto {
        val id = "photo-${++photoIds}"
        world.photoFiles.newFile(id, "jpg").writeText(id)
        return DraftPhoto(id, "$id.jpg")
    }

    @Test
    fun `photos alone can be sent, and come back with the draft`() = runTest {
        world.settings.setGraceSeconds(0)
        val first = opened()
        val photo = copy()
        first.addPhotos(listOf(photo))
        assertTrue(first.ui.value.canSend)
        assertTrue(first.ui.value.hasContent)

        val second = opened()
        assertEquals(listOf(photo), second.ui.value.photos)

        second.send()
        assertEquals(EntryState.PENDING, world.entries().single().state)
        assertEquals(listOf(photo.id), world.queue.photosOf(world.entries().single().id).map { it.id })
    }

    @Test
    fun `the editor keeps at most 10 photos, and removing one makes room`() = runTest {
        val model = opened()
        model.addPhotos(List(12) { copy() })

        assertEquals(MAX_PHOTOS, model.ui.value.photos.size)
        assertFalse(model.ui.value.canAddPhotos)

        model.removePhoto(model.ui.value.photos.first().id)
        assertTrue(model.ui.value.canAddPhotos)
        assertEquals(9, world.queue.photosOf(world.queue.draft()!!.id).size)
    }

    @Test
    fun `removing the last photo of an empty editor lets go of the draft`() = runTest {
        val model = opened()
        val photo = copy()
        model.addPhotos(listOf(photo))

        model.removePhoto(photo.id)

        assertFalse(model.ui.value.hasContent)
        assertTrue(world.entries().isEmpty())
        assertFalse(world.photoFiles.file(photo.file).exists())
    }

    @Test
    fun `Edit brings the last entry back with its photos, and the line shows how many it has`() = runTest {
        val model = opened()
        model.type("Sunset from the bridge")
        model.addPhotos(listOf(copy(), copy()))
        model.send()

        val next = opened()
        assertEquals("2 photos · Sunset from the bridge", next.ui.value.lastEntry!!.excerpt)

        next.editLast()

        assertEquals("Sunset from the bridge", next.ui.value.text)
        assertEquals(2, next.ui.value.photos.size)
    }

    @Test
    fun `an entry of photos alone shows only the count on the line`() = runTest {
        val model = opened()
        model.addPhotos(listOf(copy()))
        model.send()

        assertEquals("1 photo", opened().ui.value.lastEntry!!.excerpt)
    }

    @Test
    fun `Confirm discard deletes the photos with the text`() = runTest {
        val model = opened()
        val photo = copy()
        model.addPhotos(listOf(photo))
        model.askDiscard()

        model.confirmDiscard()

        assertTrue(world.entries().isEmpty())
        assertFalse(world.photoFiles.file(photo.file).exists())
    }
}
