package io.github.leoalfaroc.jot2press

import io.github.leoalfaroc.jot2press.data.CategoryNotice
import io.github.leoalfaroc.jot2press.data.Entry
import io.github.leoalfaroc.jot2press.data.EntryState
import io.github.leoalfaroc.jot2press.data.STUCK_AFTER_MILLIS
import io.github.leoalfaroc.jot2press.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueFactsTest {
    private val now = 1_800_000_000_000L
    private var ids = 0

    private fun entry(state: EntryState, failingSince: Long? = null, lastError: String? = null) =
        Entry(id = "id-${++ids}", text = "Coffee", state = state, failingSince = failingSince, lastError = lastError)

    private fun facts(vararg entries: Entry) = queueFacts(entries.toList(), now)

    @Test
    fun `the queue is counted by state, and the draft is not part of it`() {
        val facts = facts(entry(EntryState.DRAFT), entry(EntryState.GRACE), entry(EntryState.PENDING), entry(EntryState.FAILED))

        assertEquals(QueueFacts(waiting = 2, failed = 1, stuck = 0), facts)
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
        val quiet = listOf(entry(EntryState.GRACE), entry(EntryState.PENDING), entry(EntryState.DRAFT), entry(EntryState.PENDING, failingSince = now - 60_000))
        val settings = Settings(configured = true, categoryNotice = CategoryNotice("Travel", "Journal"))

        assertEquals(WidgetState(configured = true), widgetState(quiet, settings, now))
    }

    @Test
    fun `the widget counts the entries that failed or are stuck, and says when the sign-in is rejected`() {
        val stuck = entry(EntryState.PENDING, failingSince = now - 2 * STUCK_AFTER_MILLIS)

        assertEquals(WidgetState(Trouble.NotSent(2, null)), widgetState(listOf(entry(EntryState.FAILED), stuck, entry(EntryState.PENDING)), Settings(), now))
        assertEquals(WidgetState(Trouble.SignInRejected), widgetState(listOf(entry(EntryState.PENDING)), Settings(authError = true), now))
    }

    @Test
    fun `there is nothing to fix while entries only wait`() {
        val waiting = listOf(entry(EntryState.DRAFT), entry(EntryState.GRACE), entry(EntryState.PENDING, failingSince = now - 1, lastError = "Bad gateway"))
        assertEquals(null, trouble(waiting, Settings(configured = true), now))
    }

    @Test
    fun `one entry that could not be sent comes with what the site answered`() {
        val refused = entry(EntryState.FAILED, lastError = "Sorry, you are not allowed to create posts.")
        assertEquals(Trouble.NotSent(1, "Sorry, you are not allowed to create posts."), trouble(listOf(refused), Settings(configured = true), now))

        val stuck = entry(EntryState.PENDING, failingSince = now - 2 * STUCK_AFTER_MILLIS, lastError = "Bad gateway")
        assertEquals(Trouble.NotSent(1, "Bad gateway"), trouble(listOf(stuck), Settings(configured = true), now))
    }

    @Test
    fun `several are only counted, their reasons are in the queue`() {
        val entries = listOf(entry(EntryState.FAILED, lastError = "A"), entry(EntryState.PENDING, failingSince = now - 2 * STUCK_AFTER_MILLIS, lastError = "B"))
        assertEquals(Trouble.NotSent(2, null), trouble(entries, Settings(configured = true), now))
    }

    @Test
    fun `a rejected sign-in comes before entries that could not be sent`() {
        val entries = listOf(entry(EntryState.FAILED, lastError = "A"))
        assertEquals(Trouble.SignInRejected, trouble(entries, Settings(configured = true, authError = true), now))
    }

    @Test
    fun `the red mark shows the same as the widget's badge`() {
        assertEquals("!", Trouble.SignInRejected.mark)
        assertEquals("3", Trouble.NotSent(3, null).mark)
        assertEquals("99+", Trouble.NotSent(120, null).mark)
    }
}
