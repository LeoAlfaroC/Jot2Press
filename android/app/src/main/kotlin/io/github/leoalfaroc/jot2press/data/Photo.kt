package io.github.leoalfaroc.jot2press.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** The most photos one entry can have, as in the plugin. */
const val MAX_PHOTOS = 10

/** One photo of an entry. Its file is the app's own copy, in `PhotoFiles`. */
@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(entity = Entry::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("entryId")],
)
data class Photo(
    /** UUID: the `id` sent to `POST /photos`, and listed in the entry's `photo_ids`. */
    @PrimaryKey val id: String,
    val entryId: String,
    /** 0 to 9: the order shown in the dialog and sent in `photo_ids`. */
    val position: Int,
    /** The name of the copy inside the photos folder. */
    val file: String,
    /** The site stored it: it is not uploaded again. */
    @ColumnInfo(defaultValue = "0") val uploaded: Boolean = false,
)

/** A photo in the editor: its id and the name of its copy. */
data class DraftPhoto(val id: String, val file: String)

/** How many photos an entry has. */
data class PhotoCount(val entryId: String, val count: Int)

fun photoCount(count: Int): String = if (count == 1) "1 photo" else "$count photos"

/** `2 photos · Sunset from the bridge`, only the count without text, only the text without photos. */
fun withPhotos(count: Int, text: String): String = when {
    count == 0 -> text
    text.isBlank() -> photoCount(count)
    else -> "${photoCount(count)} · $text"
}
