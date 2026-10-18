package io.github.leoalfaroc.jot2press.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoKindTest {
    @get:Rule val folder = TemporaryFolder()

    private fun kind(vararg head: Int, tail: String = "") =
        PhotoKind.of(folder.newFile().apply { writeBytes(head.map { it.toByte() }.toByteArray() + tail.toByteArray()) })

    @Test
    fun `it knows the types the plugin takes, HEIC, and nothing else`() {
        assertEquals(PhotoKind.JPEG, kind(0xFF, 0xD8, 0xFF, 0xE0, tail = "........"))
        assertEquals(PhotoKind.PNG, kind(0x89, tail = "PNG\r\n\u001a\n...."))
        assertEquals(PhotoKind.WEBP, kind(tail = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 "))
        assertEquals(PhotoKind.HEIC, kind(0, 0, 0, 0x18, tail = "ftypheic...."))
        assertEquals(PhotoKind.OTHER, kind(tail = "BM.........."))
        assertEquals(PhotoKind.OTHER, kind(0xFF, 0xD8))
    }
}
