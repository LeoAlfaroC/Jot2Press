package io.github.leoalfaroc.journal.queue

import io.github.leoalfaroc.journal.TestClock
import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.EntryState
import io.github.leoalfaroc.journal.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class QueueUiTest {
    private val clock = TestClock()
    private val draft = Entry(id = "draft", text = "Unfinished", state = EntryState.DRAFT)
    private val grace = sent("grace", "Coffee with\nAna", EntryState.GRACE, "14:31:00")
    private val pending = sent("pending", "Walked back", EntryState.PENDING, "14:20:00")
    private val failed = sent("failed", "Too long", EntryState.FAILED, "13:05:00").copy(lastError = "The text is too long.")

    private fun sent(id: String, text: String, state: EntryState, time: String, day: String = "2026-10-11") =
        Entry(id = id, text = text, state = state, writtenAt = "${day}T$time-05:00")

    private fun ui(vararg entries: Entry, settings: Settings = Settings(), onUnmeteredNetwork: Boolean = true) =
        queueUi(entries.toList(), settings, clock, onUnmeteredNetwork)

    @Test
    fun `it lists what is in the queue, in the order given, without the draft`() {
        val ui = ui(draft, grace, pending, failed)

        assertEquals(emptyList<String>(), ui.messages)
        assertEquals(
            listOf(
                QueueRow("grace", "14:31", "Coffee with Ana", "In its grace window"),
                QueueRow("pending", "14:20", "Walked back", "Waiting"),
                QueueRow("failed", "13:05", "Too long", "Failed", detail = "The text is too long.", canRetry = true),
            ),
            ui.rows,
        )
    }

    @Test
    fun `an entry of another day shows its date`() {
        val old = sent("old", "Yesterday's", EntryState.PENDING, "23:50:00", day = "2026-10-10")

        assertEquals("10 Oct · 23:50", ui(old).rows.single().time)
    }

    @Test
    fun `a row starts with its number of photos, and shows only that for an entry of photos alone`() {
        val photosOnly = sent("photos", "", EntryState.PENDING, "14:25:00")

        val rows = queueUi(listOf(grace, photosOnly), Settings(), clock, true, photoCounts = mapOf("grace" to 2, "photos" to 1)).rows

        assertEquals("2 photos · Coffee with Ana", rows[0].text)
        assertEquals("1 photo", rows[1].text)
    }

    @Test
    fun `an entry the server keeps refusing shows the answer and since when, and after an hour the screen says why the rest wait`() {
        val began = clock.millis()
        val refused = pending.copy(lastError = "The site answered with an error (HTTP 503).", failingSince = began)

        clock.advance(Duration.ofMinutes(59))
        val early = ui(refused)
        assertEquals("Server error since 14:32 · retrying", early.rows.single().status)
        assertEquals("The site answered with an error (HTTP 503).", early.rows.single().detail)
        assertEquals(emptyList<String>(), early.messages)

        clock.advance(Duration.ofMinutes(2))
        assertEquals(listOf(STUCK_MESSAGE), ui(refused).messages)
    }

    @Test
    fun `it says so when nothing is sent because the credentials were rejected`() {
        assertEquals(listOf(AUTH_MESSAGE), ui(pending, settings = Settings(authError = true, wifiOnly = true), onUnmeteredNetwork = false).messages)
        assertEquals(listOf(AUTH_MESSAGE), ui(settings = Settings(authError = true)).messages)
    }

    @Test
    fun `it says that entries wait for Wi-Fi only when the setting is what holds them`() {
        val wifiOnly = Settings(wifiOnly = true)

        assertEquals(listOf(WIFI_MESSAGE), ui(pending, settings = wifiOnly, onUnmeteredNetwork = false).messages)
        assertEquals(emptyList<String>(), ui(pending, settings = wifiOnly, onUnmeteredNetwork = true).messages)
        assertEquals(emptyList<String>(), ui(pending, settings = Settings(), onUnmeteredNetwork = false).messages)
        assertEquals(emptyList<String>(), ui(grace, failed, settings = wifiOnly, onUnmeteredNetwork = false).messages)
    }
}
