package io.github.leoalfaroc.journal.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TestClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

fun memoryDb(): JournalDb =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), JournalDb::class.java)
        .allowMainThreadQueries()
        .build()

@RunWith(AndroidJUnit4::class)
class QueueTest {
    private val clock = TestClock()
    private val db = memoryDb()
    private var ids = 0
    private val queue = Queue(db, clock) { "id-${++ids}" }

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
}
