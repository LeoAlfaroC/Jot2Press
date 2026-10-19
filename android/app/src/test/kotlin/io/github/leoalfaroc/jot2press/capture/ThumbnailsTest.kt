package io.github.leoalfaroc.jot2press.capture

import androidx.compose.ui.graphics.ImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.jot2press.data.DraftPhoto
import io.github.leoalfaroc.jot2press.data.MAX_PHOTOS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailsTest {
    private val decoded = mutableListOf<String>()
    private val thumbnails = Thumbnails { photo, _ -> decoded += photo.id; ImageBitmap(1, 1) }

    private fun photo(n: Int) = DraftPhoto("photo-$n", "photo-$n.jpg")

    @Test
    fun `a photo that comes back into sight is not decoded again`() = runTest {
        val first = thumbnails.of(photo(1), 216)
        thumbnails.of(photo(2), 216)

        assertSame(first, thumbnails.of(photo(1), 216))
        assertEquals(listOf("photo-1", "photo-2"), decoded)
    }

    @Test
    fun `every photo of a full editor stays decoded`() = runTest {
        for (n in 1..MAX_PHOTOS) thumbnails.of(photo(n), 216)
        for (n in 1..MAX_PHOTOS) thumbnails.of(photo(n), 216)

        assertEquals(MAX_PHOTOS, decoded.size)
    }

    @Test
    fun `a photo that cannot be read is tried again next time`() = runTest {
        var reads = 0
        val failing = Thumbnails { _, _ -> reads++; null }

        assertNull(failing.of(photo(1), 216))
        assertNull(failing.of(photo(1), 216))
        assertEquals(2, reads)
    }
}
