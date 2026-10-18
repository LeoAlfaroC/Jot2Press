package io.github.leoalfaroc.jot2press.capture

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.data.DraftPhoto
import io.github.leoalfaroc.jot2press.data.PhotoFiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.io.InputStream

@RunWith(AndroidJUnit4::class)
class PhotoIntakeTest {
    @get:Rule val folder = TemporaryFolder()
    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    private val files by lazy { PhotoFiles(folder.newFolder("photos")) }
    private val intake by lazy { PhotoIntake(resolver, files) { "photo-1" } }
    private val jpeg = byteArrayOf(-1, -40, -1, -32, 1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun `a chosen image is copied under its id, with the extension of what it really is`() {
        val uri = Uri.parse("content://media/picker/0/1")
        shadowOf(resolver).registerInputStreamSupplier(uri) { jpeg.inputStream() }

        val photo = intake.copy(uri)!!

        assertEquals(DraftPhoto("photo-1", "photo-1.jpg"), photo)
        assertArrayEquals(jpeg, files.file(photo.file).readBytes())
    }

    @Test
    fun `an image that cannot be read adds nothing and leaves nothing`() {
        val uri = Uri.parse("content://media/picker/0/2")
        shadowOf(resolver).registerInputStreamSupplier(uri) {
            object : InputStream() {
                override fun read(): Int = throw IOException("Gone")
            }
        }

        assertNull(intake.copy(uri))
        assertEquals(0, folder.root.resolve("photos").list()!!.size)
    }

    @Test
    fun `a camera that wrote nothing adds nothing`() {
        val photo = intake.newCameraPhoto()
        files.file(photo.file).createNewFile()

        assertNull(intake.taken(photo))
        assertEquals(false, files.file(photo.file).exists())
    }
}
