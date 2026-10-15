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
    /** The server has been failing on an entry for more than an hour. */
    val stuck: Boolean = false,
    val hasDraft: Boolean = false,
) {
    val total: Int get() = waiting + failed
}

fun queueFacts(entries: List<Entry>, now: Long): QueueFacts = QueueFacts(
    waiting = entries.count { it.state == EntryState.GRACE || it.state == EntryState.PENDING },
    failed = entries.count { it.state == EntryState.FAILED },
    stuck = entries.any { it.isStuck(now) },
    hasDraft = entries.any { it.state == EntryState.DRAFT },
)

/** The one line that the queue row in settings shows. */
fun queueSummary(facts: QueueFacts, authError: Boolean): String = when {
    authError -> if (facts.total == 0) "Password rejected" else "${facts.total} held: password rejected"
    facts.stuck -> "${facts.total} held: server error"
    facts.failed > 0 && facts.waiting > 0 -> "${facts.failed} failed, ${facts.waiting} waiting"
    facts.failed > 0 -> "${facts.failed} failed"
    facts.waiting > 0 -> "${facts.waiting} waiting"
    else -> "Empty"
}

data class WidgetState(
    /** Entries in `GRACE`, `PENDING` or `FAILED`. The widget hides it when zero. */
    val count: Int = 0,
    val hasDraft: Boolean = false,
    val alert: Boolean = false,
    /** False until `Test connection` has succeeded: the write button then opens settings. */
    val configured: Boolean = false,
)

fun widgetState(facts: QueueFacts, settings: Settings): WidgetState = WidgetState(
    count = facts.total,
    hasDraft = facts.hasDraft,
    alert = settings.authError || facts.failed > 0 || facts.stuck || settings.categoryNotice != null,
    configured = settings.configured,
)
