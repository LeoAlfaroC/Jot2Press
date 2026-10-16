package io.github.leoalfaroc.journal

import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.EntryState
import io.github.leoalfaroc.journal.data.Settings
import io.github.leoalfaroc.journal.data.isStuck

/** What the widget and the queue row need to know about the queue. */
data class QueueFacts(
    /** Entries in `GRACE` or `PENDING`. */
    val waiting: Int = 0,
    val failed: Int = 0,
    /** Waiting entries the server has been failing on for more than an hour. */
    val stuck: Int = 0,
    val hasDraft: Boolean = false,
) {
    val total: Int get() = waiting + failed
}

fun queueFacts(entries: List<Entry>, now: Long): QueueFacts = QueueFacts(
    waiting = entries.count { it.state == EntryState.GRACE || it.state == EntryState.PENDING },
    failed = entries.count { it.state == EntryState.FAILED },
    stuck = entries.count { it.isStuck(now) },
    hasDraft = entries.any { it.state == EntryState.DRAFT },
)

/** The one line that the queue row in settings shows. */
fun queueSummary(facts: QueueFacts, authError: Boolean): String = when {
    authError -> if (facts.total == 0) "Password rejected" else "${facts.total} held: password rejected"
    facts.stuck > 0 -> "${facts.total} held: server error"
    facts.failed > 0 && facts.waiting > 0 -> "${facts.failed} failed, ${facts.waiting} waiting"
    facts.failed > 0 -> "${facts.failed} failed"
    facts.waiting > 0 -> "${facts.waiting} waiting"
    else -> "Empty"
}

/** The widget speaks only when the user has something to fix: waiting entries and a draft show nothing. */
data class WidgetState(
    /** Entries the site refused, and entries stuck on server errors. */
    val failed: Int = 0,
    /** Nothing is sent until the credentials are fixed. */
    val signInRejected: Boolean = false,
    /** False until credentials are saved: the write button then opens settings. */
    val configured: Boolean = false,
)

fun widgetState(facts: QueueFacts, settings: Settings): WidgetState = WidgetState(
    failed = facts.failed + facts.stuck,
    signInRejected = settings.authError,
    configured = settings.configured,
)
