package io.github.leoalfaroc.jot2press.location

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.leoalfaroc.jot2press.R

/** Lets the search for a position go on after the dialog has closed: Android allows it only to a foreground service. */
interface SearchService {
    /** False when Android does not let it start. */
    fun start(): Boolean
    fun stop()
}

class AndroidSearchService(
    private val context: Context,
    /** Android 14 and later refuse a location service started with no activity in sight, and crash the app when it times out. */
    private val onScreen: () -> Boolean,
) : SearchService {
    override fun start(): Boolean {
        if (!onScreen()) return false
        synchronized(LocationService.lock) { LocationService.wanted = true }
        return try {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
            true
        } catch (e: IllegalStateException) {
            // Android 12 and later refuse it from the background, with ForegroundServiceStartNotAllowedException.
            refused()
        } catch (e: SecurityException) {
            refused()
        }
    }

    private fun refused(): Boolean {
        synchronized(LocationService.lock) { LocationService.wanted = false }
        return false
    }

    override fun stop() {
        synchronized(LocationService.lock) {
            LocationService.wanted = false
            // A service not yet in the foreground is stopped by itself once it gets there.
            if (LocationService.foreground) context.stopService(Intent(context, LocationService::class.java))
        }
    }
}

/** Only stands in the foreground, with its notification, while the app's search needs location. The search runs elsewhere. */
class LocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, searchNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            val stillWanted = synchronized(lock) {
                foreground = true
                wanted
            }
            if (!stillWanted) {
                // The search ended before this service reached the foreground, where Android needs it to be.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } catch (e: Exception) {
            // Android refused the foreground: no permission any more, or too late. The entry keeps what it has.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        synchronized(lock) { foreground = false }
        super.onDestroy()
    }

    /**
     * Whether the search wants the service, and whether it is in the foreground yet, read and written under one lock.
     * Android crashes the app if a service started for the foreground is stopped before it gets there, so a stop
     * that comes first is left to the service itself, and one that comes after it is made at once.
     */
    internal companion object {
        private const val NOTIFICATION_ID = 1
        val lock = Any()
        var wanted = false
        var foreground = false

        fun reset() = synchronized(lock) {
            wanted = false
            foreground = false
        }
    }
}

const val SEARCH_CHANNEL = "location"
const val SEARCH_TITLE = "Getting a precise location for your entry"
const val SEARCH_TEXT = "This stops by itself in a minute."

/** On a silent channel: no sound, no vibration. */
fun searchNotification(context: Context): Notification {
    NotificationManagerCompat.from(context).createNotificationChannel(
        NotificationChannelCompat.Builder(SEARCH_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Location")
            .setSound(null, null)
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build(),
    )
    return NotificationCompat.Builder(context, SEARCH_CHANNEL)
        .setSmallIcon(R.drawable.ic_location_on)
        .setContentTitle(SEARCH_TITLE)
        .setContentText(SEARCH_TEXT)
        .setOngoing(true)
        .setSilent(true)
        // Shown at once: Android 12 and later would otherwise hold it back for up to 10 seconds, most of its life.
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}
