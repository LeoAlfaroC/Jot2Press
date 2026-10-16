package io.github.leoalfaroc.journal.location

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.TestClock
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AndroidLocationSourceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(LocationManager::class.java)
    private val clock = TestClock()
    private val gps = LocationManager.GPS_PROVIDER
    private val network = LocationManager.NETWORK_PROVIDER
    private val source = AndroidLocationSource(app, clock, manager) { listOf(gps, network) }

    @Before
    fun providersOn() {
        // Android's own clock moves on, so that a position can be minutes old.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10))
        shadowOf(manager).setLocationEnabled(true)
        shadowOf(manager).setProviderEnabled(gps, true)
        shadowOf(manager).setProviderEnabled(network, true)
    }

    private fun location(provider: String, accuracy: Float, ageMillis: Long = 0, lat: Double = 4.6) = Location(provider).apply {
        latitude = lat
        longitude = -74.08
        this.accuracy = accuracy
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMillis * 1_000_000
    }

    @Test
    fun `the permission is precise, approximate or none, as granted`() {
        assertEquals(Permission.NONE, source.permission())

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(Permission.APPROXIMATE, source.permission())

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(Permission.PRECISE, source.permission())
    }

    @Test
    fun `the last known position is the newest of the providers, timed by its age, and there is none without a permission`() = runTest {
        shadowOf(manager).setLastKnownLocation(gps, location(gps, 8f, ageMillis = 90_000, lat = 1.0))
        shadowOf(manager).setLastKnownLocation(network, location(network, 40f, ageMillis = 30_000, lat = 2.0))
        assertNull(source.lastKnown())

        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        assertEquals(Fix(2.0, -74.08, 40f, clock.millis() - 30_000), source.lastKnown())
    }

    @Test
    fun `positions come while they are collected, and stop when the collection ends`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val got = mutableListOf<Fix>()
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) { source.updates().collect { got += it } }

        shadowOf(manager).simulateLocation(location(gps, 6f, lat = 3.0))
        runCurrent()
        assertEquals(listOf(Fix(3.0, -74.08, 6f, clock.millis())), got)

        listening.cancel()
        shadowOf(manager).simulateLocation(location(gps, 5f))
        runCurrent()
        assertEquals(1, got.size)
    }

    @Test
    fun `without a permission the positions end at once`() = runTest {
        assertEquals(emptyList<Fix>(), source.updates().toList())
    }

    @Test
    fun `before Android 12 the GPS and network providers are used together`() {
        assertEquals(listOf(gps, network), providersFor(manager, sdk = Build.VERSION_CODES.R))
    }
}
