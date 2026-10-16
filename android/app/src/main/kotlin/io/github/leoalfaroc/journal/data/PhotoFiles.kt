package io.github.leoalfaroc.journal.data

import java.io.File

/** The app's own copies of the photos of the draft and of the entries waiting to be sent. Gallery originals are never here. */
class PhotoFiles(private val folder: File) {
    fun file(name: String): File = File(folder, name)

    /** Where the copy of the photo with this id goes. The file itself is not created. */
    fun newFile(id: String, extension: String): File {
        folder.mkdirs()
        return File(folder, "$id.$extension")
    }

    fun delete(names: Collection<String>) {
        names.forEach { File(folder, it).delete() }
    }

    /**
     * Deletes the copies that no photo names, last written before [before].
     * A newer one may still be on its way into the queue, or may be a camera shot whose dialog Android recreated.
     */
    fun sweep(kept: Set<String>, before: Long) {
        folder.listFiles()?.forEach { if (it.name !in kept && it.lastModified() < before) it.delete() }
    }
}
