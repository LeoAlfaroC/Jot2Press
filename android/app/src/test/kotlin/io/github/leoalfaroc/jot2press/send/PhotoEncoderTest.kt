package io.github.leoalfaroc.jot2press.send

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.data.PhotoKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Real pixels: Robolectric's native graphics decode and encode as a phone does. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEncoderTest {
    @get:Rule val folder = TemporaryFolder()
    private val encoder by lazy { PhotoEncoder(folder.newFolder("cache")) }

    /** Random colours compress badly, so the file is large for its size, as a photo is. */
    private fun noisy(width: Int, height: Int): Bitmap {
        val random = Random(7)
        return Bitmap.createBitmap(IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun write(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 100, name: String = "photo"): File =
        folder.newFile(name).apply { outputStream().use { bitmap.compress(format, quality, it) } }

    private fun size(file: File): Pair<Int, Int> =
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.let { it.outWidth to it.outHeight }

    /** A 24-bit BMP: a type the plugin does not take, which BitmapFactory still reads. */
    private fun bmp(width: Int, height: Int): File {
        val row = (width * 3 + 3) / 4 * 4
        val buffer = ByteBuffer.allocate(54 + row * height).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + row * height).putInt(0).putInt(54)
        buffer.putInt(40).putInt(width).putInt(height).putShort(1).putShort(24).putInt(0).putInt(row * height).putInt(2835).putInt(2835).putInt(0).putInt(0)
        repeat(height) { repeat(row) { buffer.put(0x80.toByte()) } }
        return folder.newFile("photo.bmp").apply { writeBytes(buffer.array()) }
    }

    @Test
    fun `a JPEG or PNG within the limit goes as it is`() {
        val jpeg = write(noisy(64, 48), Bitmap.CompressFormat.JPEG, name = "a.jpg")
        val png = write(noisy(64, 48), Bitmap.CompressFormat.PNG, name = "b.png")

        val sentJpeg = encoder.prepare(jpeg, limit = jpeg.length())
        val sentPng = encoder.prepare(png, limit = png.length())

        assertEquals(jpeg, sentJpeg.file)
        assertEquals("image/jpeg", sentJpeg.mediaType)
        assertFalse(sentJpeg.temporary)
        assertEquals(png, sentPng.file)
        assertEquals("image/png", sentPng.mediaType)
    }

    @Test
    fun `a photo over the limit comes out a JPEG under it, and the copy is left as it was`() {
        val source = write(noisy(800, 600), Bitmap.CompressFormat.PNG)
        val before = source.readBytes()
        val limit = 250_000L

        val sent = encoder.prepare(source, limit)

        assertTrue(sent.temporary)
        assertEquals("image/jpeg", sent.mediaType)
        assertEquals(PhotoKind.JPEG, PhotoKind.of(sent.file))
        assertTrue("${sent.file.length()} > $limit", sent.file.length() <= limit)
        assertEquals("quality drops before the dimensions do", 800 to 600, size(sent.file))
        assertArrayEquals(before, source.readBytes())
    }

    @Test
    fun `when lower quality is not enough, the photo gets smaller`() {
        val source = write(noisy(800, 600), Bitmap.CompressFormat.JPEG)

        val sent = encoder.prepare(source, limit = 20_000)

        val (width, height) = size(sent.file)
        assertTrue(sent.file.length() <= 20_000)
        assertTrue(width < 800 && height < 600)
        assertEquals(800f / 600f, width.toFloat() / height, 0.02f)
    }

    @Test
    fun `a type the plugin does not take becomes a JPEG even when small`() {
        val source = bmp(40, 30)

        val sent = encoder.prepare(source, limit = 10_000_000)

        assertTrue(sent.temporary)
        assertEquals(PhotoKind.JPEG, PhotoKind.of(sent.file))
        assertEquals(40 to 30, size(sent.file))
    }

    @Test
    fun `a re-encoded photo comes out upright`() {
        val source = write(noisy(80, 40), Bitmap.CompressFormat.JPEG)
        ExifInterface(source).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val sent = encoder.prepare(source, limit = source.length() - 1)

        assertEquals(40 to 80, size(sent.file))
    }

    @Test
    fun `a transparent PNG is put on white, not black`() {
        val clear = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) }
        val source = write(clear, Bitmap.CompressFormat.PNG)

        val sent = encoder.prepare(source, limit = 1)

        // With limit = 1 the photo shrinks to its smallest try, so the centre is read from what came out.
        val sentBitmap = BitmapFactory.decodeFile(sent.file.path)
        val pixel = sentBitmap.getPixel(sentBitmap.width / 2, sentBitmap.height / 2)
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    @Test
    fun `a huge photo is read at most 8192 pixels long`() {
        val source = write(Bitmap.createBitmap(9000, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }, Bitmap.CompressFormat.PNG)

        val sent = encoder.prepare(source, limit = 1)

        assertTrue(size(sent.file).first <= 8192)
    }

    @Test(expected = UnreadablePhoto::class)
    fun `a copy that is not an image cannot be prepared`() {
        encoder.prepare(folder.newFile().apply { writeText("not a photo") }, limit = 1)
    }

    @Test(expected = UnreadablePhoto::class)
    fun `a copy that is gone cannot be prepared`() {
        encoder.prepare(File(folder.root, "gone.jpg"), limit = 1_000_000)
    }
}
