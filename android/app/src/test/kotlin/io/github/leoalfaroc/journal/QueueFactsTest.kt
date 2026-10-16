package io.github.leoalfaroc.journal

import io.github.leoalfaroc.journal.data.CategoryNotice
import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.EntryState
import io.github.leoalfaroc.journal.data.STUCK_AFTER_MILLIS
import io.github.leoalfaroc.journal.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueFactsTest {
    private val now = 1_800_000_000_000L
    private var ids = 0

    private fun entry(state: EntryState, failingSince: Long? = null) =
        Entry(id = "id-${++ids}", text = "Coffee", state = state, failingSince = failingSince)

    private fun facts(vararg entries: Entry) = queueFacts(entries.toList(), now)

    @Test
    fun `the queue is counted by state, and the draft is not part of it`() {
        val facts = facts(entry(EntryState.DRAFT), entry(EntryState.GRACE), entry(EntryState.PENDING), entry(EntryState.FAILED))

        assertEquals(QueueFacts(waiting = 2, failed = 1, stuck = 0, hasDraft = true), facts)
        assertEquals(3, facts.total)
    }

    @Test
    fun `an entry is stuck once the server has failed on it for more than an hour`() {
        assertEquals(0, facts(entry(EntryState.PENDING, failingSince = now - STUCK_AFTER_MILLIS)).stuck)
        assertEquals(1, facts(entry(EntryState.PENDING, failingSince = now - STUCK_AFTER_MILLIS - 1)).stuck)
        assertEquals(0, facts(entry(EntryState.PENDING)).stuck)
    }

    @Test
    fun `the queue row says what the queue holds`() {
        val pending = entry(EntryState.PENDING)
        val grace = entry(EntryState.GRACE)
        val failed = entry(EntryState.FAILED)
        val stuck = entry(EntryState.PENDING, failingSince = now - 2 * STUCK_AFTER_MILLIS)

        assertEquals("Empty", queueSummary(facts(), authError = false))
        assertEquals("Empty", queueSummary(facts(entry(EntryState.DRAFT)), authError = false))
        assertEquals("3 waiting", queueSummary(facts(pending, grace, pending.copy(id = "other")), authError = false))
        assertEquals("1 failed, 2 waiting", queueSummary(facts(failed, pending, grace), authError = false))
        assertEquals("1 failed", queueSummary(facts(failed), authError = false))
        assertEquals("3 held: server error", queueSummary(facts(stuck, pending, grace), authError = false))
        assertEquals("3 held: server error", queueSummary(facts(stuck, pending, failed), authError = false))
        assertEquals("3 held: password rejected", queueSummary(facts(stuck, pending, failed), authError = true))
        assertEquals("Password rejected", queueSummary(facts(), authError = true))
    }

    @Test
    fun `the widget shows nothing for waiting entries, a draft or a category notice`() {
        val quiet = facts(entry(EntryState.GRACE), entry(EntryState.PENDING), entry(EntryState.DRAFT), entry(EntryState.PENDING, failingSince = now - 60_000))
        val settings = Settings(configured = true, categoryNotice = CategoryNotice("Travel", "Journal"))

        assertEquals(WidgetState(configured = true), widgetState(quiet, settings))
    }

    @Test
    fun `the widget counts the entries that failed or are stuck, and says when the sign-in is rejected`() {
        val stuck = entry(EntryState.PENDING, failingSince = now - 2 * STUCK_AFTER_MILLIS)

        assertEquals(WidgetState(failed = 2), widgetState(facts(entry(EntryState.FAILED), stuck, entry(EntryState.PENDING)), Settings()))
        assertEquals(WidgetState(signInRejected = true), widgetState(facts(entry(EntryState.PENDING)), Settings(authError = true)))
    }
}
