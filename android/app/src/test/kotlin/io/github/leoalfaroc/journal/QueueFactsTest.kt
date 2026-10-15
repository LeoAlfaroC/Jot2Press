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

        assertEquals(QueueFacts(waiting = 2, failed = 1, stuck = false, hasDraft = true), facts)
        assertEquals(3, facts.total)
    }

    @Test
    fun `an entry is stuck once the server has failed on it for more than an hour`() {
        assertEquals(false, facts(entry(EntryState.PENDING, failingSince = now - STUCK_AFTER_MILLIS)).stuck)
        assertEquals(true, facts(entry(EntryState.PENDING, failingSince = now - STUCK_AFTER_MILLIS - 1)).stuck)
        assertEquals(false, facts(entry(EntryState.PENDING)).stuck)
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
    fun `the widget counts the queue, marks a draft, and alerts when something needs attention`() {
        val quiet = widgetState(facts(entry(EntryState.GRACE), entry(EntryState.PENDING)), Settings(configured = true))
        assertEquals(WidgetState(count = 2, hasDraft = false, alert = false, configured = true), quiet)

        assertEquals(WidgetState(hasDraft = true), widgetState(facts(entry(EntryState.DRAFT)), Settings()))

        val stuck = entry(EntryState.PENDING, failingSince = now - 2 * STUCK_AFTER_MILLIS)
        assertEquals(true, widgetState(facts(entry(EntryState.FAILED)), Settings()).alert)
        assertEquals(true, widgetState(facts(stuck), Settings()).alert)
        assertEquals(true, widgetState(facts(), Settings(authError = true)).alert)
        assertEquals(true, widgetState(facts(), Settings(categoryNotice = CategoryNotice("Travel", "Journal"))).alert)
        assertEquals(false, widgetState(facts(entry(EntryState.PENDING, failingSince = now - 60_000)), Settings()).alert)
    }
}
