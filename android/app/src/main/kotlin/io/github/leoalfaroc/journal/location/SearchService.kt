package io.github.leoalfaroc.journal.location

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
import io.github.leoalfaroc.journal.R

/** Lets the search for a position go on after the dialog has closed: Android allows it only to a foreground service. */
interface SearchService {
    /** False when Android does not let it start. */
    fun start(): Boolean
    fun stop()
}

class AndroidSearchService(private val context: Context) : SearchService {
    override fun start(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        true
    } catch (e: IllegalStateException) {
        // Android 12 and later refuse it from the background, with ForegroundServiceStartNotAllowedException.
        false
    } catch (e: SecurityException) {
        false
    }

    override fun stop() {
        context.stopService(Intent(context, LocationService::class.java))
    }
}

/** Only stands in the foreground, with its notification, while the app's search needs location. The search runs elsewhere. */
class LocationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, searchNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            // Android refused the foreground: no permission any more, or too late. The entry keeps what it has.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val NOTIFICATION_ID = 1
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
