package io.github.leoalfaroc.journal.capture

import java.io.File

/** The phone's gallery, where a photo taken with the camera button is also saved. It belongs to the user from then on. */
fun interface Gallery {
    /** Saves a copy of [photo]. Throws when it cannot; the entry keeps its own copy either way. */
    fun save(photo: File)
}
