package io.github.leoalfaroc.jot2press.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.TestClock
import io.github.leoalfaroc.jot2press.location.Fix
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
import java.time.Instant
import java.time.ZoneOffset

fun memoryDb(): Jot2PressDb =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), Jot2PressDb::class.java)
        .allowMainThreadQueries()
        .build()

@RunWith(AndroidJUnit4::class)
class QueueTest {
    private val clock = TestClock()
    private val db = memoryDb()
    private var ids = 0
    @get:Rule val folder = TemporaryFolder()
    private val files by lazy { PhotoFiles(folder.newFolder("photos")) }
    private val queue by lazy { Queue(db, clock, files) { "id-${++ids}" } }

    @After
    fun close() = db.close()

    /** Writes and sends an entry, then moves the clock on by a minute. */
    private suspend fun sent(text: String, graceSeconds: Int = 0): Entry {
        queue.saveDraft(text)
        val entry = queue.send(graceSeconds)!!
        clock.advance(Duration.ofMinutes(1))
        return entry
    }

    private suspend fun stored(id: String): Entry? = queue.observe().first().firstOrNull { it.id == id }

    private var photoIds = 0

    /** A copy on disk, as the dialog makes it when a photo is chosen. */
    private fun copy(): DraftPhoto {
        val id = "photo-${++photoIds}"
        files.newFile(id, "jpg").writeText(id)
        return DraftPhoto(id, "$id.jpg")
    }

    private fun onDisk(photo: DraftPhoto) = files.file(photo.file).exists()

    @Test
    fun `photos alone make a draft, which can be sent`() = runTest {
        val photo = copy()
        queue.saveDraft("", listOf(photo))

        val draft = queue.draft()!!
        assertEquals(listOf(photo.id), queue.photosOf(draft.id).map { it.id })

        val sent = queue.send(graceSeconds = 0)!!
        assertEquals(EntryState.PENDING, sent.state)
    }

    @Test
    fun `the draft's photos follow the editor, in order, and a removed one's copy is deleted`() = runTest {
        val (a, b, c) = listOf(copy(), copy(), copy())
        queue.saveDraft("Coffee", listOf(a, b, c))
        queue.saveDraft("Coffee", listOf(c, a))

        val draft = queue.draft()!!
        assertEquals(listOf(c.id to 0, a.id to 1), queue.photosOf(draft.id).map { it.id to it.position })
        assertFalse(onDisk(b))
        assertTrue(onDisk(a) && onDisk(c))
    }

    @Test
    fun `discarding, deleting and a confirmed send delete the copies`() = runTest {
        val kept = copy()
        queue.saveDraft("Coffee", listOf(kept))
        queue.discardDraft()
        assertFalse(onDisk(kept))

        val deleted = copy()
        queue.saveDraft("Walk", listOf(deleted))
        val walk = queue.send(graceSeconds = 0)!!
        queue.delete(walk.id)
        assertFalse(onDisk(deleted))

        val confirmed = copy()
        queue.saveDraft("Dinner", listOf(confirmed))
        val dinner = queue.send(graceSeconds = 0)!!
        queue.markSent(dinner.id)
        assertFalse(onDisk(confirmed))
        assertTrue(queue.photoFileNames().isEmpty())
    }

    @Test
    fun `an entry taken back from its grace window keeps its photos, and letting it go deletes them`() = runTest {
        val photo = copy()
        queue.saveDraft("Coffee", listOf(photo))
        val sent = queue.send(graceSeconds = 60)!!

        val back = queue.takeBack(sent.id)!!
        assertEquals(listOf(photo.id), queue.photosOf(back.id).map { it.id })

        queue.saveDraft("", emptyList(), afresh = true)
        assertNull(queue.draft())
        assertFalse(onDisk(photo))
    }

    @Test
    fun `letting go of a taken-back entry keeps the photos the new draft still holds`() = runTest {
        val photo = copy()
        queue.saveDraft("Coffee", listOf(photo))
        val sent = queue.send(graceSeconds = 60)!!
        queue.takeBack(sent.id)

        queue.saveDraft("Something else", listOf(photo), afresh = true)

        val draft = queue.draft()!!
        assertTrue(draft.id != sent.id)
        assertEquals(listOf(photo.id), queue.photosOf(draft.id).map { it.id })
        assertTrue(onDisk(photo))
    }

    @Test
    fun `retrying after unknown_photo uploads the photos again, other failures keep them uploaded`() = runTest {
        val (a, b) = listOf(copy(), copy())
        queue.saveDraft("Coffee", listOf(a))
        val coffee = queue.send(graceSeconds = 0)!!
        queue.saveDraft("Walk", listOf(b))
        val walk = queue.send(graceSeconds = 0)!!
        queue.markUploaded(a.id)
        queue.markUploaded(b.id)

        queue.markFailed(coffee.id, "Photo has not been uploaded.", UNKNOWN_PHOTO)
        queue.markFailed(walk.id, "Category is invalid.", "invalid_category")
        assertEquals(UNKNOWN_PHOTO, stored(coffee.id)!!.lastErrorCode)
        queue.retry(coffee.id)
        queue.retry(walk.id)

        assertFalse(queue.photosOf(coffee.id).single().uploaded)
        assertTrue(queue.photosOf(walk.id).single().uploaded)
        assertNull(stored(coffee.id)!!.lastErrorCode)
    }

    @Test
    fun `photo counts are kept per entry`() = runTest {
        queue.saveDraft("Coffee", listOf(copy(), copy()))
        val draft = queue.draft()!!

        assertEquals(mapOf(draft.id to 2), queue.observePhotoCounts().first())
    }

    @Test
    fun `typed text is kept as the one draft`() = runTest {
        queue.saveDraft("Coffee")
        queue.saveDraft("Coffee with Ana")

        val all = queue.observe().first()
        assertEquals(1, all.size)
        assertEquals("Coffee with Ana", queue.draft()!!.text)
        assertEquals(EntryState.DRAFT, all.single().state)
        assertNull(all.single().writtenAt)
    }

    @Test
    fun `text that is empty or only spaces is not a draft`() = runTest {
        queue.saveDraft("  \n ")
        assertNull(queue.draft())

        queue.saveDraft("Coffee")
        queue.saveDraft("")
        assertNull(queue.draft())
    }

    @Test
    fun `discarding removes the draft`() = runTest {
        queue.saveDraft("Coffee")
        queue.discardDraft()
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `sending starts the grace window and stamps the time with seconds and offset`() = runTest {
        queue.saveDraft("Coffee")
        val entry = queue.send(graceSeconds = 60)!!

        assertEquals(EntryState.GRACE, entry.state)
        assertEquals("2026-10-11T14:32:05-05:00", entry.writtenAt)
        assertEquals(clock.millis(), entry.writtenAtMillis)
        assertEquals(clock.millis() + 60_000, entry.graceEndsAt)
        assertEquals(entry, stored(entry.id))
        assertNull(queue.draft())
    }

    @Test
    fun `the seconds are written when they are zero and UTC is written as an offset`() = runTest {
        clock.now = Instant.parse("2026-10-11T19:32:00.900Z")
        clock.zoneId = ZoneOffset.UTC
        queue.saveDraft("Coffee")
        assertEquals("2026-10-11T19:32:00+00:00", queue.send(60)!!.writtenAt)
    }

    @Test
    fun `the time is stamped in the zone the phone is in when the entry is sent`() = runTest {
        clock.zoneId = ZoneOffset.ofHours(2)
        queue.saveDraft("Late")
        val entry = queue.send(60)!!
        assertEquals("2026-10-11T21:32:05+02:00", entry.writtenAt)
        assertEquals("21:32", entry.time)
        assertEquals("2026-10-11", entry.day.toString())
    }

    @Test
    fun `with a grace window of zero the entry is ready at once`() = runTest {
        queue.saveDraft("Coffee")
        val entry = queue.send(graceSeconds = 0)!!
        assertEquals(EntryState.PENDING, entry.state)
        assertNull(entry.graceEndsAt)
    }

    @Test
    fun `there is nothing to send without a draft`() = runTest {
        assertNull(queue.send(60))
    }

    @Test
    fun `an entry becomes pending when its grace window ends`() = runTest {
        queue.saveDraft("First")
        val first = queue.send(60)!!
        clock.advance(Duration.ofSeconds(30))
        queue.saveDraft("Second")
        val second = queue.send(60)!!

        clock.advance(Duration.ofSeconds(29))
        assertEquals(0, queue.promoteDue())
        clock.advance(Duration.ofSeconds(1))
        assertEquals(1, queue.promoteDue())

        assertEquals(EntryState.PENDING, stored(first.id)!!.state)
        assertNull(stored(first.id)!!.graceEndsAt)
        assertEquals(EntryState.GRACE, stored(second.id)!!.state)
        assertEquals(listOf(second.id), queue.inGrace().map { it.id })
    }

    @Test
    fun `an entry taken back is the draft again and keeps its time when sent again`() = runTest {
        queue.saveDraft("Cofee")
        val first = queue.send(60)!!
        clock.advance(Duration.ofSeconds(20))

        val back = queue.takeBack(first.id)!!
        assertEquals(EntryState.DRAFT, back.state)
        assertEquals(back, queue.draft())

        queue.saveDraft("Coffee")
        val again = queue.send(60)!!
        assertEquals(first.id, again.id)
        assertEquals("Coffee", again.text)
        assertEquals(first.writtenAt, again.writtenAt)
        assertEquals(first.writtenAtMillis, again.writtenAtMillis)
        assertEquals(clock.millis() + 60_000, again.graceEndsAt)
        assertEquals(1, queue.observe().first().size)
    }

    @Test
    fun `letting go of an entry taken back removes it and keeps what was typed instead, in one step`() = runTest {
        queue.saveDraft("Tea")
        val tea = queue.send(60)!!
        queue.takeBack(tea.id)

        queue.saveDraft("Water", afresh = true)

        val water = queue.observe().first().single()
        assertEquals("Water", water.text)
        assertTrue(water.id != tea.id)
        assertEquals(EntryState.DRAFT, water.state)
        assertNull(water.writtenAt)

        // A draft that was never sent is the same draft however it is saved.
        queue.saveDraft("Water, cold", afresh = true)
        assertEquals(water.copy(text = "Water, cold"), queue.observe().first().single())

        // With nothing typed instead, letting go only removes.
        val cold = queue.send(60)!!
        queue.takeBack(cold.id)
        queue.saveDraft("", afresh = true)
        assertTrue(queue.observe().first().isEmpty())
    }

    @Test
    fun `an entry cannot be taken back while the editor holds text or after its window ended`() = runTest {
        queue.saveDraft("First")
        val first = queue.send(60)!!
        queue.saveDraft("Something new")
        assertNull(queue.takeBack(first.id))
        assertEquals("Something new", queue.draft()!!.text)
        assertEquals(EntryState.GRACE, stored(first.id)!!.state)

        queue.discardDraft()
        clock.advance(Duration.ofSeconds(60))
        assertNull(queue.takeBack(first.id))
        assertNull(queue.takeBack("no-such-entry"))
    }

    @Test
    fun `the latest entry counts only while it is the most recent and inside its window`() = runTest {
        val older = sent("Older", graceSeconds = 600)
        assertEquals(older.id, queue.latestInGrace()!!.id)

        sent("Newer", graceSeconds = 0)
        assertNull(queue.latestInGrace())

        val newest = sent("Newest", graceSeconds = 120)
        assertEquals(newest.id, queue.latestInGrace()!!.id)
        clock.advance(Duration.ofMinutes(1))
        assertNull(queue.latestInGrace())
    }

    @Test
    fun `the oldest entry is sent first even when it became pending last`() = runTest {
        val first = sent("First")
        val second = sent("Second")
        queue.markFailed(first.id, "Rejected")
        assertEquals(second.id, queue.nextPending()!!.id)

        queue.retry(first.id)
        assertEquals(first.id, queue.nextPending()!!.id)
    }

    @Test
    fun `a confirmed entry leaves the phone`() = runTest {
        val entry = sent("Coffee")
        queue.markSent(entry.id)
        assertTrue(queue.observe().first().isEmpty())
        assertNull(queue.nextPending())
    }

    @Test
    fun `a rejected entry is failed with the message and retry makes it pending again`() = runTest {
        val entry = sent("Coffee")
        queue.recordServerError(entry.id, "HTTP 500")
        queue.markFailed(entry.id, "Text is not valid.")

        val failed = stored(entry.id)!!
        assertEquals(EntryState.FAILED, failed.state)
        assertEquals("Text is not valid.", failed.lastError)
        assertNull(failed.failingSince)

        queue.retry(entry.id)
        assertEquals(entry.copy(state = EntryState.PENDING), stored(entry.id))
    }

    @Test
    fun `a server error is kept with the moment it began and the entry is stuck after an hour`() = runTest {
        val entry = sent("Coffee")
        val began = clock.millis()
        queue.recordServerError(entry.id, "HTTP 500")
        clock.advance(Duration.ofMinutes(60))
        queue.recordServerError(entry.id, "HTTP 503")

        val failing = stored(entry.id)!!
        assertEquals(EntryState.PENDING, failing.state)
        assertEquals("HTTP 503", failing.lastError)
        assertEquals(began, failing.failingSince)
        assertFalse(failing.isStuck(clock.millis()))
        assertTrue(failing.isStuck(clock.millis() + 1))

        queue.clearServerError(entry.id)
        assertEquals(entry, stored(entry.id))
        assertFalse(stored(entry.id)!!.isStuck(clock.millis() + 1))
    }

    @Test
    fun `deleting removes an entry in any state and a missing entry changes nothing`() = runTest {
        val entry = sent("Coffee", graceSeconds = 60)
        queue.delete(entry.id)
        queue.markFailed(entry.id, "Rejected")
        queue.retry(entry.id)
        assertTrue(queue.observe().first().isEmpty())
    }

    private fun fix(accuracy: Float, lat: Double) = Fix(lat, -74.08175, accuracy, clock.millis())

    @Test
    fun `Send stores whether the entry attaches its location, with the position it takes`() = runTest {
        queue.saveDraft("Coffee")
        val entry = queue.send(60, attachLocation = true, position = fix(30f, lat = 4.6))!!

        val kept = stored(entry.id)!!
        assertTrue(kept.attachLocation)
        assertEquals(4.6, kept.lat!!, 0.0)
        assertEquals(-74.08175, kept.lng!!, 0.0)
        assertEquals(kept, entry)
    }

    @Test
    fun `with the pin off no position is stored`() = runTest {
        queue.saveDraft("Coffee")
        val entry = queue.send(60, attachLocation = false, position = fix(30f, lat = 4.6))!!

        assertFalse(stored(entry.id)!!.attachLocation)
        assertNull(stored(entry.id)!!.lat)
    }

    @Test
    fun `an entry sent again after Edit keeps its position, and keeps it on the phone when the pin is then off`() = runTest {
        queue.saveDraft("Coffee")
        val entry = queue.send(60, attachLocation = true, position = fix(30f, lat = 4.6))!!
        queue.takeBack(entry.id)
        queue.send(60, attachLocation = true, position = fix(5f, lat = 9.9))
        assertEquals(4.6, stored(entry.id)!!.lat!!, 0.0)

        queue.takeBack(entry.id)
        queue.send(60, attachLocation = false)

        val kept = stored(entry.id)!!
        assertFalse(kept.attachLocation)
        assertEquals(4.6, kept.lat!!, 0.0)
    }

    @Test
    fun `a better position is stored only while the entry is in its grace window`() = runTest {
        queue.saveDraft("Coffee")
        val entry = queue.send(60, attachLocation = true)!!
        assertTrue(queue.stillInGrace(entry.id))
        assertTrue(queue.placeLocation(entry.id, fix(12f, lat = 4.6)))
        assertEquals(4.6, stored(entry.id)!!.lat!!, 0.0)

        queue.takeBack(entry.id)
        assertFalse(queue.stillInGrace(entry.id))
        assertFalse(queue.placeLocation(entry.id, fix(8f, lat = 5.0)))

        queue.send(60, attachLocation = true)
        clock.advance(Duration.ofSeconds(60))
        // Still GRACE until the sender promotes it, but its window has ended.
        assertEquals(EntryState.GRACE, stored(entry.id)!!.state)
        assertFalse(queue.stillInGrace(entry.id))
        assertFalse(queue.placeLocation(entry.id, fix(8f, lat = 5.0)))
        assertEquals(4.6, stored(entry.id)!!.lat!!, 0.0)
    }
}
