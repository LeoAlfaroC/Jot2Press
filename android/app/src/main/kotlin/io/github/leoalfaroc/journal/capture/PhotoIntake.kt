package io.github.leoalfaroc.journal.capture

import android.content.ContentResolver
import android.net.Uri
import io.github.leoalfaroc.journal.data.DraftPhoto
import io.github.leoalfaroc.journal.data.PhotoFiles
import io.github.leoalfaroc.journal.data.PhotoKind
import java.util.UUID

/**
 * Makes the app's own copy of a photo the moment it is chosen: the picker's grant on the original lasts only as long
 * as the dialog, and an entry can wait days. Call off the main thread.
 */
class PhotoIntake(
    private val resolver: ContentResolver,
    private val files: PhotoFiles,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Copies a chosen image. Null, with nothing left behind, when it cannot be read or written. */
    fun copy(uri: Uri): DraftPhoto? {
        val id = newId()
        val part = files.newFile(id, "part")
        return try {
            val input = resolver.openInputStream(uri) ?: return null
            input.use { source -> part.outputStream().use { source.copyTo(it) } }
            val kept = files.newFile(id, PhotoKind.of(part).extension)
            if (part.length() == 0L || !part.renameTo(kept)) return null
            DraftPhoto(id, kept.name)
        } catch (e: Exception) {
            null
        } finally {
            part.delete()
        }
    }

    /** Where the camera app writes the next photo. */
    fun newCameraPhoto(): DraftPhoto = cameraPhoto(newId())

    /** The photo the camera was asked to write, from its id alone: the id is what survives the dialog being recreated. */
    fun cameraPhoto(id: String): DraftPhoto = DraftPhoto(id, files.newFile(id, "jpg").name)

    /** The camera came back. The photo when it wrote one; otherwise null, and its empty file is gone. */
    fun taken(photo: DraftPhoto): DraftPhoto? {
        if (files.file(photo.file).length() > 0) return photo
        files.delete(listOf(photo.file))
        return null
    }
}
