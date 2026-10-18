package io.github.leoalfaroc.jot2press.send

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import io.github.leoalfaroc.jot2press.data.PhotoKind
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** The file to upload for a photo, and its type. A temporary file is deleted once the request is done. */
class Prepared(val file: File, val mediaType: String, val temporary: Boolean)

/** The copy on the phone cannot be read as an image. Trying again will not change that. */
class UnreadablePhoto : Exception("The photo cannot be read.")

/**
 * Makes a photo fit the site. The copy is never changed: a retry prepares the same file again.
 * Re-encoding keeps the orientation, applied to the pixels, and drops every other piece of metadata.
 */
class PhotoEncoder(private val cache: File) {
    fun prepare(source: File, limit: Long): Prepared {
        if (!source.isFile) throw UnreadablePhoto()
        val kind = PhotoKind.of(source)
        if (kind.mediaType != null && source.length() <= limit) return Prepared(source, kind.mediaType, temporary = false)

        val (width, height) = imageSize(source) ?: throw UnreadablePhoto()
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DECODE_SIDE) sample *= 2
        while (true) {
            // The size is known to be an image, so a failed decode or a lack of memory is not "unreadable": try again at half the size.
            val prepared = try {
                attempt(source, kind, sample, limit)
            } catch (e: OutOfMemoryError) {
                null
            }
            if (prepared != null) return prepared
            sample *= 2
            if (maxOf(width, height) / sample < MIN_SIDE) throw UnreadablePhoto()
        }
    }

    /** Null when the photo could not be decoded at this sample size. */
    private fun attempt(source: File, kind: PhotoKind, sample: Int, limit: Long): Prepared? {
        val decoded = decodeUpright(source, sample) ?: return null
        var opaque = decoded
        try {
            opaque = onWhite(decoded)
            // A type the site does not take, but small enough, only needs a new type: one try at quality 95 first.
            val qualities = if (kind.mediaType == null && source.length() <= limit) listOf(95) + QUALITIES else QUALITIES
            return encode(opaque, qualities, limit)
        } finally {
            if (opaque !== decoded) opaque.recycle()
            decoded.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, qualities: List<Int>, limit: Long): Prepared {
        cache.mkdirs()
        val out = File.createTempFile("upload", ".jpg", cache)
        try {
            for (quality in qualities) {
                write(bitmap, quality, out)
                if (out.length() <= limit) return Prepared(out, JPEG, temporary = true)
            }
            var scale = 1f
            while (true) {
                scale *= SHRINK
                val width = (bitmap.width * scale).roundToInt()
                val height = (bitmap.height * scale).roundToInt()
                // Nothing fits: the smallest try goes, and the site says whether it takes it.
                if (width < MIN_SIDE || height < MIN_SIDE) return Prepared(out, JPEG, temporary = true)
                val smaller = Bitmap.createScaledBitmap(bitmap, width, height, true)
                try {
                    write(smaller, qualities.last(), out)
                } finally {
                    smaller.recycle()
                }
                if (out.length() <= limit) return Prepared(out, JPEG, temporary = true)
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
    }

    private fun write(bitmap: Bitmap, quality: Int, out: File) {
        out.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("The photo could not be encoded.") }
    }

    /** JPEG has no transparency: what was transparent would come out black. */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return opaque
    }

    private companion object {
        const val JPEG = "image/jpeg"
        val QUALITIES = listOf(90, 80, 70, 60)
        const val SHRINK = 0.8f
        const val MIN_SIDE = 16
        /** A 200 MP photo would need some 800 MB of pixels: larger ones are read at a power-of-two fraction. */
        const val MAX_DECODE_SIDE = 8192
    }
}

/** Width and height as stored, before any rotation. Null when it is not an image. */
fun imageSize(file: File): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/** The image at 1/[sampleSize] of its size, turned as its EXIF orientation says. Null when it cannot be decoded. */
fun decodeUpright(file: File, sampleSize: Int): Bitmap? {
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return null
    val turn = orientation(file) ?: return bitmap
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true).also { if (it !== bitmap) bitmap.recycle() }
    } catch (e: Throwable) {
        bitmap.recycle()
        throw e
    }
}

private fun orientation(file: File): Matrix? {
    val exif = try {
        ExifInterface(file)
    } catch (e: Exception) {
        return null
    }
    val matrix = Matrix()
    if (exif.isFlipped) matrix.postScale(-1f, 1f)
    matrix.postRotate(exif.rotationDegrees.toFloat())
    return matrix.takeUnless { it.isIdentity }
}
