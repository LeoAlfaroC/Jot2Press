package io.github.leoalfaroc.journal.location

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.leoalfaroc.journal.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SearchServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun freshState() = LocationService.reset()

    @Test
    fun `the service stands in the foreground with its silent notification`() {
        AndroidSearchService(app) { true }.start()
        val service = Robolectric.buildService(LocationService::class.java).create().startCommand(0, 1).get()

        val notification = shadowOf(service).lastForegroundNotification
        assertEquals(SEARCH_TITLE, notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(SEARCH_TEXT, notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(R.drawable.ic_location_on, notification.smallIcon.resId)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(SEARCH_CHANNEL)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test
    fun `starting and stopping go to the service`() {
        val search = AndroidSearchService(app) { true }

        assertTrue(search.start())
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStartedService.component?.className)

        Robolectric.buildService(LocationService::class.java).create().startCommand(0, 1)
        search.stop()
        assertEquals(LocationService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
    }

    @Test
    fun `a stop before the service reaches the foreground waits for it, and then the service stops itself`() {
        val search = AndroidSearchService(app) { true }
        search.start()

        search.stop()
        assertNull(shadowOf(app).nextStoppedService)

        val controller = Robolectric.buildService(LocationService::class.java).create().startCommand(0, 1)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(shadowOf(controller.get()).isForegroundStopped)
        assertFalse(shadowOf(controller.get()).isLastForegroundNotificationAttached)
    }

    @Test
    fun `a stop once the service is in the foreground stops it`() {
        val search = AndroidSearchService(app) { true }
        search.start()
        Robolectric.buildService(LocationService::class.java).create().startCommand(0, 1)

        search.stop()

        assertEquals(LocationService::class.java.name, shadowOf(app).nextStoppedService.component?.className)
    }

    @Test
    fun `a service that is still wanted stays in the foreground`() {
        AndroidSearchService(app) { true }.start()

        val service = Robolectric.buildService(LocationService::class.java).create().startCommand(0, 1).get()

        assertFalse(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
    }

    @Test
    fun `with no dialog on screen nothing is started`() {
        val search = AndroidSearchService(app) { false }

        assertFalse(search.start())

        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `the app declares the location permissions and the service`() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toSet()
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        )
        assertTrue(requested.containsAll(needed))
        assertEquals(LocationService::class.java.name, app.packageManager.getServiceInfo(ComponentName(app, LocationService::class.java), 0).name)
    }
}
