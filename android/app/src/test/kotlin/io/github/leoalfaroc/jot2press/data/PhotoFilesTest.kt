package io.github.leoalfaroc.jot2press.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `the sweep deletes old copies nothing names, and keeps named and recent ones`() {
        val files = PhotoFiles(folder.newFolder("photos"))
        val named = files.newFile("a", "jpg").apply { writeText("a"); setLastModified(1_000) }
        val orphan = files.newFile("b", "jpg").apply { writeText("b"); setLastModified(1_000) }
        val recent = files.newFile("c", "jpg").apply { writeText("c"); setLastModified(5_000) }

        files.sweep(kept = setOf("a.jpg"), before = 2_000)

        assertEquals(setOf(named.name, recent.name), folder.root.resolve("photos").list()!!.toSet())
        assertEquals(false, orphan.exists())
    }
}
