package io.github.leoalfaroc.journal.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.OffsetDateTime

enum class EntryState {
    /** Being written or left unfinished. At most one exists. */
    DRAFT,

    /** Sent by the user; waiting for the grace window to end. */
    GRACE,

    /** Ready to send, or waiting for a retry. */
    PENDING,

    /** The server rejected this specific entry. */
    FAILED,
}

@Entity(tableName = "entries")
data class Entry(
    @PrimaryKey val id: String,
    val text: String,
    val state: EntryState,
    /** When the entry was first sent, as `2026-10-11T14:32:05-05:00`. Null while it has never been sent. */
    val writtenAt: String? = null,
    /** The same moment in epoch milliseconds, to put entries in order. */
    val writtenAtMillis: Long? = null,
    /** Epoch milliseconds at which the grace window ends. */
    val graceEndsAt: Long? = null,
    /** What the server last answered, when it refused or failed. */
    val lastError: String? = null,
    /** Epoch milliseconds since which the server has been failing on this entry. */
    val failingSince: Long? = null,
    /** Where the entry was written. Sent only while `attachLocation` is set. */
    val lat: Double? = null,
    val lng: Double? = null,
    /** The plugin's error code for the last refusal, as `unknown_photo`. */
    val lastErrorCode: String? = null,
    /** The entry sends its position: its pin was on when it was last sent. A position kept with this off stays on the phone. */
    @ColumnInfo(defaultValue = "0") val attachLocation: Boolean = false,
)

/** A pending entry is stuck once the server has failed on it for this long. */
const val STUCK_AFTER_MILLIS = 60 * 60 * 1000L

fun Entry.isStuck(now: Long): Boolean =
    state == EntryState.PENDING && failingSince != null && now - failingSince > STUCK_AFTER_MILLIS

/** The day the entry belongs to: the date of `writtenAt` in its own offset. */
val Entry.day: LocalDate? get() = writtenAt?.let { OffsetDateTime.parse(it).toLocalDate() }

/** The entry's time as `HH:mm`, in its own offset. */
val Entry.time: String? get() = writtenAt?.substring(11, 16)
