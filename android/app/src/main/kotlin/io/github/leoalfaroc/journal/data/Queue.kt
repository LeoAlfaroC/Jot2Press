package io.github.leoalfaroc.journal.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The entries still on the phone, and every change of state they can go through.
 * Each function is one transaction, so the dialog, the sender and the queue screen never see half a change.
 */
class Queue(
    private val db: JournalDb,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.entries()

    /** Every entry on the phone, the draft included, newest first. */
    fun observe(): Flow<List<Entry>> = dao.observeAll()

    suspend fun draft(): Entry? = dao.draft()

    /**
     * Keeps what is in the editor. Text that is empty or only spaces is not a draft.
     * `afresh` says the editor has let go of the entry it had taken back from its grace window:
     * that entry is removed, and what is kept is a new one, with its own id and no time yet.
     */
    suspend fun saveDraft(text: String, afresh: Boolean = false) {
        db.withTransaction {
            val held = dao.draft()
            // Only an entry that was sent before has a time.
            val kept = held?.takeUnless { afresh && it.writtenAt != null }
            if (held != null && (kept == null || text.isBlank())) dao.delete(held.id)
            if (text.isNotBlank()) dao.put(kept?.copy(text = text) ?: Entry(id = newId(), text = text, state = EntryState.DRAFT))
        }
    }

    suspend fun discardDraft() {
        db.withTransaction { dao.draft()?.let { dao.delete(it.id) } }
    }

    /**
     * Sends the draft: it waits out the grace window, or is ready at once when the window is 0.
     * `writtenAt` is set the first time an entry is sent and never changes afterwards.
     * Returns null when there is nothing to send.
     */
    suspend fun send(graceSeconds: Int): Entry? = db.withTransaction {
        val draft = dao.draft()?.takeIf { it.text.isNotBlank() } ?: return@withTransaction null
        val now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)
        val sent = draft.copy(
            state = if (graceSeconds > 0) EntryState.GRACE else EntryState.PENDING,
            writtenAt = draft.writtenAt ?: WRITTEN_AT.format(now),
            writtenAtMillis = draft.writtenAtMillis ?: now.toInstant().toEpochMilli(),
            graceEndsAt = if (graceSeconds > 0) clock.millis() + graceSeconds * 1000L else null,
            lastError = null,
            failingSince = null,
        )
        dao.put(sent)
        sent
    }

    /** Moves the entries whose grace window has ended to `PENDING`. Returns how many. */
    suspend fun promoteDue(): Int = dao.promoteDue(clock.millis())

    suspend fun inGrace(): List<Entry> = dao.inGrace()

    /** The most recent sent entry, when it is still inside its grace window. */
    suspend fun latestInGrace(): Entry? = dao.latestSent()?.takeIf { it.isInGrace() }

    /**
     * Takes an entry back from its grace window into the editor.
     * Returns null, and changes nothing, when the window has ended or the editor already holds text.
     */
    suspend fun takeBack(id: String): Entry? = db.withTransaction {
        val entry = dao.byId(id)?.takeIf { it.isInGrace() } ?: return@withTransaction null
        if (dao.draft() != null) return@withTransaction null
        entry.copy(state = EntryState.DRAFT, graceEndsAt = null).also { dao.put(it) }
    }

    suspend fun delete(id: String) = dao.delete(id)

    /** The next entry to send: the oldest `writtenAt` first. */
    suspend fun nextPending(): Entry? = dao.nextPending()

    /** The server confirmed the entry: nothing of it stays on the phone. */
    suspend fun markSent(id: String) = dao.delete(id)

    /** The server rejected this entry itself. */
    suspend fun markFailed(id: String, message: String) = change(id) {
        if (it.state == EntryState.PENDING) it.copy(state = EntryState.FAILED, lastError = message, failingSince = null) else it
    }

    /** The server failed on this entry. Keeps its answer, and the moment it first happened. */
    suspend fun recordServerError(id: String, message: String) = change(id) {
        if (it.state == EntryState.PENDING) it.copy(lastError = message, failingSince = it.failingSince ?: clock.millis()) else it
    }

    suspend fun clearServerError(id: String) = change(id) {
        if (it.state == EntryState.PENDING) it.copy(lastError = null, failingSince = null) else it
    }

    suspend fun retry(id: String) = change(id) {
        if (it.state == EntryState.FAILED) it.copy(state = EntryState.PENDING, lastError = null, failingSince = null) else it
    }

    private fun Entry.isInGrace(): Boolean = state == EntryState.GRACE && (graceEndsAt ?: 0) > clock.millis()

    private suspend fun change(id: String, change: (Entry) -> Entry) {
        db.withTransaction { dao.byId(id)?.let { dao.put(change(it)) } }
    }

    companion object {
        /** Always with seconds and a numeric offset: the plugin rejects `14:32-05:00`. */
        val WRITTEN_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    }
}
