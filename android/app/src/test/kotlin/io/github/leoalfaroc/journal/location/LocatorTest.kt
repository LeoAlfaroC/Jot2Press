package io.github.leoalfaroc.journal.location

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TestWorld
import io.github.leoalfaroc.journal.data.Entry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class LocatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val world by lazy { TestWorld(folder.root) }
    private val locator get() = world.container.locator

    @Before
    fun precise() {
        world.location.permission = Permission.PRECISE
    }

    @After
    fun close() = world.close()

    /** The capture dialog, as the owner of the listening. */
    private val dialog = Any()

    private suspend fun lat(id: String): Double? = world.entries().single { it.id == id }.lat

    /** Writes and sends an entry with the pin on, as the dialog does, and hands it to the search. */
    private suspend fun sent(text: String, graceSeconds: Int = 600, pick: Pick? = null): Entry {
        world.queue.saveDraft(text)
        val entry = world.queue.send(graceSeconds, attachLocation = true, position = pick?.fix)!!
        locator.follow(entry, pick)
        return entry
    }

    @Test
    fun `at Send the best position found while the dialog listened is taken, before any last known one`() = runTest {
        locator.opened()
        locator.listen(dialog, true)
        world.position(world.fix(40f, lat = 1.0))
        world.position(world.fix(15f, lat = 2.0))
        world.position(world.fix(30f, lat = 3.0))
        world.location.last = world.fix(5f, lat = 9.0)

        assertEquals(Pick(world.fix(15f, lat = 2.0), live = true), locator.pick())
    }

    @Test
    fun `without a live position a last known one is taken when it is at most 2 minutes old`() = runTest {
        locator.opened()
        assertNull(locator.pick())

        world.location.last = world.fix(30f, ageMillis = 120_000)
        assertEquals(Pick(world.fix(30f, ageMillis = 120_000), live = false), locator.pick())

        world.location.last = world.fix(30f, ageMillis = 120_001)
        assertNull(locator.pick())
    }

    @Test
    fun `a new dialog does not take the last one's position`() = runTest {
        locator.opened()
        locator.listen(dialog, true)
        world.position(world.fix(15f))
        locator.listen(dialog, false)

        locator.opened()

        assertNull(locator.pick())
    }

    @Test
    fun `the dialog listens only while it asks to, and no service runs for it`() = runTest {
        locator.listen(dialog, true)
        world.location.subscribers.first { it > 0 }
        assertFalse(world.search.running)

        locator.listen(dialog, false)
        world.location.subscribers.first { it == 0 }
    }

    @Test
    fun `after Send a more accurate position replaces the stored one, and a less accurate one does not`() = runTest {
        val entry = sent("Coffee", pick = Pick(world.fix(50f, lat = 1.0), live = true))
        assertTrue(world.search.running)

        world.position(world.fix(80f, lat = 2.0))
        assertEquals(1.0, lat(entry.id)!!, 0.0)

        world.position(world.fix(30f, lat = 3.0))
        assertEquals(3.0, lat(entry.id)!!, 0.0)
        assertTrue(world.search.running)
    }

    @Test
    fun `a live position replaces a last known one, even a more accurate one`() = runTest {
        val entry = sent("Coffee", pick = Pick(world.fix(25f, lat = 1.0), live = false))

        world.position(world.fix(40f, lat = 2.0))
        assertEquals(2.0, lat(entry.id)!!, 0.0)

        world.position(world.fix(45f, lat = 3.0))
        assertEquals(2.0, lat(entry.id)!!, 0.0)
    }

    @Test
    fun `the search ends at the first position within 20 m, and nothing listens after it`() = runTest {
        val entry = sent("Coffee")

        world.position(world.fix(20f, lat = 5.0))

        assertEquals(5.0, lat(entry.id)!!, 0.0)
        assertFalse(world.search.running)
        world.location.subscribers.first { it == 0 }
    }

    @Test
    fun `the search ends 60 seconds after Send even when no position comes`() = runTest {
        val entry = sent("Coffee", pick = Pick(world.fix(50f, lat = 1.0), live = false))

        world.clock.advance(Duration.ofSeconds(59))
        world.tick()
        assertTrue(world.search.running)

        world.clock.advance(Duration.ofSeconds(1))
        world.tick()
        assertFalse(world.search.running)
        assertEquals(1.0, lat(entry.id)!!, 0.0)
    }

    @Test
    fun `with a grace window shorter than a minute the search ends with it, and a later position is not stored`() = runTest {
        val entry = sent("Coffee", graceSeconds = 10)

        world.clock.advance(Duration.ofSeconds(10))
        world.position(world.fix(5f, lat = 5.0))

        assertNull(lat(entry.id))
        assertFalse(world.search.running)
    }

    @Test
    fun `Edit and Delete end the search for that entry only`() = runTest {
        val one = sent("One")
        val two = sent("Two")

        world.queue.takeBack(two.id)
        world.tick()
        assertTrue(world.search.running)

        world.position(world.fix(30f, lat = 3.0))
        assertEquals(3.0, lat(one.id)!!, 0.0)
        assertNull(lat(two.id))

        world.queue.delete(one.id)
        world.tick()
        assertFalse(world.search.running)
    }

    @Test
    fun `one stream serves two entries, and the service stops when both are done`() = runTest {
        val first = sent("One")
        world.clock.advance(Duration.ofSeconds(30))
        val second = sent("Two")

        world.position(world.fix(30f, lat = 1.0))
        assertEquals(1.0, lat(first.id)!!, 0.0)
        assertEquals(1.0, lat(second.id)!!, 0.0)

        world.clock.advance(Duration.ofSeconds(30))
        world.position(world.fix(25f, lat = 2.0))
        assertEquals(1.0, lat(first.id)!!, 0.0)
        assertEquals(2.0, lat(second.id)!!, 0.0)
        assertTrue(world.search.running)

        world.clock.advance(Duration.ofSeconds(30))
        world.tick()
        assertFalse(world.search.running)
        assertEquals(1, world.search.starts)
    }

    @Test
    fun `there is no search with an approximate permission, a grace window of 0, or a position within 20 m`() = runTest {
        world.location.permission = Permission.APPROXIMATE
        sent("Approximate")
        world.location.permission = Permission.PRECISE
        sent("At once", graceSeconds = 0)
        sent("Close", pick = Pick(world.fix(20f), live = false))

        assertEquals(0, world.search.starts)
        assertEquals(0, world.location.subscribers.value)
    }

    @Test
    fun `when Android refuses the service the entry keeps what it has and nothing listens`() = runTest {
        world.search.refuse = true

        val entry = sent("Coffee", pick = Pick(world.fix(50f, lat = 1.0), live = true))

        assertFalse(world.search.running)
        assertEquals(0, world.location.subscribers.value)
        assertEquals(1.0, lat(entry.id)!!, 0.0)
    }

    @Test
    fun `the service stays while a dialog listens, and stops once it no longer does`() = runTest {
        locator.listen(dialog, true)
        sent("Coffee")
        world.position(world.fix(10f))
        assertTrue(world.search.running)

        locator.listen(dialog, false)

        assertFalse(world.search.running)
    }
}
