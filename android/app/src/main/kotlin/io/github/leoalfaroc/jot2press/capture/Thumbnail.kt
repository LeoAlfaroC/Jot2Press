package io.github.leoalfaroc.jot2press.capture

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.leoalfaroc.jot2press.data.DraftPhoto
import io.github.leoalfaroc.jot2press.data.MAX_PHOTOS
import io.github.leoalfaroc.jot2press.send.decodeUpright
import io.github.leoalfaroc.jot2press.send.imageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A small upright copy of a photo, at least [sizePx] on its shorter side. Null when it cannot be read. Call off the main thread. */
fun thumbnail(file: File, sizePx: Int): ImageBitmap? {
    val (width, height) = imageSize(file) ?: return null
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= sizePx) sample *= 2
    return decodeUpright(file, sample)?.asImageBitmap()
}

/**
 * The thumbnails of the editor's photos, kept while the dialog lives: a photo scrolled back into the row, or a dialog
 * recreated, does not read and decode its copy again. A photo that could not be read is tried again next time.
 */
class Thumbnails(private val decode: (DraftPhoto, Int) -> ImageBitmap?) {
    private val kept = LruCache<String, ImageBitmap>(MAX_PHOTOS)

    suspend fun of(photo: DraftPhoto, sizePx: Int): ImageBitmap? {
        val key = "${photo.id}@$sizePx"
        kept[key]?.let { return it }
        return withContext(Dispatchers.IO) { decode(photo, sizePx) }?.also { kept.put(key, it) }
    }
}
