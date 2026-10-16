package io.github.leoalfaroc.journal.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoDaoTest {
    private val db = memoryDb()

    @After
    fun close() = db.close()

    @Test
    fun `photos come back in their order, are counted per entry, and go when their entry goes`() = runTest {
        db.entries().put(Entry(id = "e", text = "", state = EntryState.DRAFT))
        db.photos().insert(listOf(Photo("b", "e", 1, "b.jpg"), Photo("a", "e", 0, "a.jpg")))

        assertEquals(listOf("a", "b"), db.photos().of("e").map { it.id })
        assertEquals(listOf(PhotoCount("e", 2)), db.photos().observeCounts().first())

        db.entries().delete("e")

        assertTrue(db.photos().files().isEmpty())
    }

    @Test
    fun `an upsert of the entry keeps its photos`() = runTest {
        val entry = Entry(id = "e", text = "Coffee", state = EntryState.DRAFT)
        db.entries().put(entry)
        db.photos().insert(listOf(Photo("a", "e", 0, "a.jpg")))

        db.entries().put(entry.copy(text = "Coffee with Ana"))

        assertEquals(1, db.photos().of("e").size)
    }
}
