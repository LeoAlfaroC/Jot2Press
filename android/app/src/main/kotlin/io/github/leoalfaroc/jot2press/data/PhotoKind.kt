package io.github.leoalfaroc.jot2press.data

import java.io.File

/** What a photo is, read from its first bytes: the file name and the type the picker reported can both be wrong. */
enum class PhotoKind(val extension: String, val mediaType: String?) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    HEIC("heic", null),
    OTHER("img", null);

    companion object {
        private val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1")

        fun of(file: File): PhotoKind {
            val head = ByteArray(12)
            val read = try {
                file.inputStream().use { it.read(head) }
            } catch (e: Exception) {
                return OTHER
            }
            if (read < 12) return OTHER
            fun ascii(from: Int) = String(head, from, 4, Charsets.US_ASCII)
            return when {
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> JPEG
                head[0] == 0x89.toByte() && ascii(1).startsWith("PNG") -> PNG
                ascii(0) == "RIFF" && ascii(8) == "WEBP" -> WEBP
                ascii(4) == "ftyp" && ascii(8) in HEIC_BRANDS -> HEIC
                else -> OTHER
            }
        }
    }
}
