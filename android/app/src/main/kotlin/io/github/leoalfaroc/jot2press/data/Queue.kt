package io.github.leoalfaroc.jot2press.data

import androidx.room.withTransaction
import io.github.leoalfaroc.jot2press.location.Fix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val db: Jot2PressDb,
    private val clock: Clock,
    private val files: PhotoFiles,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = db.entries()
    private val photoDao = db.photos()

    /** Every entry on the phone, the draft included, newest first. */
    fun observe(): Flow<List<Entry>> = dao.observeAll()

    suspend fun draft(): Entry? = dao.draft()

    suspend fun photosOf(entryId: String): List<Photo> = photoDao.of(entryId)

    /** Deletes the copies of photos that were never kept: the editor was already full. */
    fun forget(photos: List<DraftPhoto>) = files.delete(photos.map { it.file })

    /** Every copy the queue still needs. */
    suspend fun photoFileNames(): Set<String> = photoDao.files().toSet()

    /** How many photos each entry has, by entry id. Entries without photos are not in it. */
    fun observePhotoCounts(): Flow<Map<String, Int>> = photoDao.observeCounts().map { counts -> counts.associate { it.entryId to it.count } }

    suspend fun markUploaded(photoId: String) = photoDao.markUploaded(photoId)

    /** The site no longer has this entry's photos: they are uploaded again before it is sent. */
    suspend fun forgetUploads(entryId: String) = photoDao.clearUploaded(entryId)

    /**
     * Keeps what is in the editor: its text and its photos, in order. Nothing, or only spaces, with no photo is not a draft.
     * `afresh` says the editor has let go of the entry it had taken back from its grace window:
     * that entry is removed, and what is kept is a new one, with its own id and no time yet.
     * The copies of photos the editor no longer holds are deleted.
     */
    suspend fun saveDraft(text: String, photos: List<DraftPhoto> = emptyList(), afresh: Boolean = false) {
        val held = photos.take(MAX_PHOTOS).distinctBy { it.id }
        val dropped = db.withTransaction {
            val draft = dao.draft()
            val before = draft?.let { photoDao.of(it.id) }.orEmpty()
            // Only an entry that was sent before has a time.
            val kept = draft?.takeUnless { afresh && it.writtenAt != null }
            val empty = text.isBlank() && held.isEmpty()
            if (draft != null && (kept == null || empty)) dao.delete(draft.id)
            if (!empty) {
                val entry = kept?.copy(text = text) ?: Entry(id = newId(), text = text, state = EntryState.DRAFT)
                dao.put(entry)
                photoDao.deleteOf(entry.id)
                photoDao.insert(held.mapIndexed { position, photo -> Photo(photo.id, entry.id, position, photo.file) })
            }
            before.map { it.file } - held.map { it.file }.toSet()
        }
        files.delete(dropped)
    }

    suspend fun discardDraft() {
        val gone = db.withTransaction { dao.draft()?.let { remove(it.id) }.orEmpty() }
        files.delete(gone)
    }

    /**
     * Sends the draft: it waits out the grace window, or is ready at once when the window is 0.
     * `writtenAt` is set the first time an entry is sent and never changes afterwards.
     * `attachLocation` is the pin as it was at `Send`. With it on, an entry that has no position yet takes `position`;
     * one sent again after `Edit` keeps the one it has. With it off nothing is stored, and a position kept stays unsent.
     * Returns null when there is nothing to send.
     */
    suspend fun send(graceSeconds: Int, attachLocation: Boolean = false, position: Fix? = null): Entry? = db.withTransaction {
        val draft = dao.draft()?.takeIf { it.text.isNotBlank() || photoDao.of(it.id).isNotEmpty() } ?: return@withTransaction null
        val now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)
        val taken = position?.takeIf { attachLocation && draft.lat == null }
        val sent = draft.copy(
            state = if (graceSeconds > 0) EntryState.GRACE else EntryState.PENDING,
            writtenAt = draft.writtenAt ?: WRITTEN_AT.format(now),
            writtenAtMillis = draft.writtenAtMillis ?: now.toInstant().toEpochMilli(),
            graceEndsAt = if (graceSeconds > 0) clock.millis() + graceSeconds * 1000L else null,
            lastError = null,
            lastErrorCode = null,
            failingSince = null,
            attachLocation = attachLocation,
            lat = taken?.lat ?: draft.lat,
            lng = taken?.lng ?: draft.lng,
        )
        dao.put(sent)
        sent
    }

    /** Moves the entries whose grace window has ended to `PENDING`. Returns how many. */
    suspend fun promoteDue(): Int = dao.promoteDue(clock.millis())

    suspend fun inGrace(): List<Entry> = dao.inGrace()

    /** The most recent sent entry, when it is still inside its grace window. */
    suspend fun latestInGrace(): Entry? = dao.latestSent()?.takeIf { it.isInGrace() }

    /** Whether the entry is still in its grace window: a search for its position goes on only while it is. */
    suspend fun stillInGrace(id: String): Boolean = dao.byId(id)?.isInGrace() == true

    /** Stores a better position for an entry in its grace window. False, and nothing changes, once it has left it. */
    suspend fun placeLocation(id: String, position: Fix): Boolean = dao.place(id, position.lat, position.lng, clock.millis()) > 0

    /**
     * Takes an entry back from its grace window into the editor.
     * Returns null, and changes nothing, when the window has ended or the editor already holds text.
     */
    suspend fun takeBack(id: String): Entry? = db.withTransaction {
        val entry = dao.byId(id)?.takeIf { it.isInGrace() } ?: return@withTransaction null
        if (dao.draft() != null) return@withTransaction null
        entry.copy(state = EntryState.DRAFT, graceEndsAt = null).also { dao.put(it) }
    }

    suspend fun delete(id: String) {
        files.delete(db.withTransaction { remove(id) })
    }

    /**
     * Deletes an entry that is still in its grace window. False, and nothing changes, once the window has ended:
     * the sender may already be sending it.
     */
    suspend fun deleteInGrace(id: String): Boolean {
        val gone = db.withTransaction { if (dao.byId(id)?.isInGrace() == true) remove(id) else null } ?: return false
        files.delete(gone)
        return true
    }

    /** The next entry to send: the oldest `writtenAt` first. */
    suspend fun nextPending(): Entry? = dao.nextPending()

    /** The server confirmed the entry: nothing of it stays on the phone, its photo copies included. */
    suspend fun markSent(id: String) = delete(id)

    /** The server rejected this entry, or one of its photos. [code] is the plugin's error code, when it gave one. */
    suspend fun markFailed(id: String, message: String, code: String? = null) = changePending(id) {
        it.copy(state = EntryState.FAILED, lastError = message, lastErrorCode = code, failingSince = null)
    }

    /** The server failed on this entry. Keeps its answer, and the moment it first happened. */
    suspend fun recordServerError(id: String, message: String) = changePending(id) {
        it.copy(lastError = message, failingSince = it.failingSince ?: clock.millis())
    }

    suspend fun clearServerError(id: String) = changePending(id) {
        it.copy(lastError = null, failingSince = null)
    }

    /** After `unknown_photo` the site no longer has the entry's photos: they are uploaded again. */
    suspend fun retry(id: String) = db.withTransaction {
        val entry = dao.byId(id)?.takeIf { it.state == EntryState.FAILED } ?: return@withTransaction
        if (entry.lastErrorCode == UNKNOWN_PHOTO) photoDao.clearUploaded(id)
        dao.put(entry.copy(state = EntryState.PENDING, lastError = null, lastErrorCode = null, failingSince = null))
    }

    /** Removes an entry and its photo rows inside the caller's transaction. Returns the copies to delete once it commits. */
    private suspend fun remove(id: String): List<String> {
        val names = photoDao.of(id).map { it.file }
        photoDao.deleteOf(id)
        dao.delete(id)
        return names
    }

    private fun Entry.isInGrace(): Boolean = state == EntryState.GRACE && (graceEndsAt ?: 0) > clock.millis()

    /** Changes an entry only while it is pending: one deleted, taken back or failed meanwhile stays as it is. */
    private suspend fun changePending(id: String, change: (Entry) -> Entry) {
        db.withTransaction { dao.byId(id)?.takeIf { it.state == EntryState.PENDING }?.let { dao.put(change(it)) } }
    }

    companion object {
        /** Always with seconds and a numeric offset: the plugin rejects `14:32-05:00`. */
        val WRITTEN_AT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
    }
}

/** The plugin's code for a `photo_ids` item it has no photo for. */
const val UNKNOWN_PHOTO = "unknown_photo"
