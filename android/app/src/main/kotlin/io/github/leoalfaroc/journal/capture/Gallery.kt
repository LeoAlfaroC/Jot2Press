package io.github.leoalfaroc.journal.capture

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The phone's gallery, where a photo taken with the camera button is also saved. It belongs to the user from then on. */
fun interface Gallery {
    /** Saves a copy of [photo]. Throws when it cannot; the entry keeps its own copy either way. */
    fun save(photo: File)
}

/** Saves into `Pictures/<album>` through MediaStore, which needs no storage permission from Android 10 on. */
class MediaStoreGallery(private val resolver: ContentResolver, album: String) : Gallery {
    private val folder = "${Environment.DIRECTORY_PICTURES}/${album.replace('/', '-')}"

    override fun save(photo: File) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_${NAME.format(LocalDateTime.now())}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("The gallery did not take the photo.")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("The gallery did not take the photo.")
            out.use { target -> photo.inputStream().use { it.copyTo(target) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private companion object {
        val NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
