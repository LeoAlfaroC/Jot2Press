package io.github.leoalfaroc.journal.capture

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.leoalfaroc.journal.send.decodeUpright
import io.github.leoalfaroc.journal.send.imageSize
import java.io.File

/** A small upright copy of a photo, at least [sizePx] on its shorter side. Null when it cannot be read. Call off the main thread. */
fun thumbnail(file: File, sizePx: Int): ImageBitmap? {
    val (width, height) = imageSize(file) ?: return null
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= sizePx) sample *= 2
    return decodeUpright(file, sample)?.asImageBitmap()
}
