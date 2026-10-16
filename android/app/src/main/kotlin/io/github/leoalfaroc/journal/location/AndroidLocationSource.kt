package io.github.leoalfaroc.journal.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Clock
import java.util.concurrent.Executor

/** Android's own location API, without Google Play Services. */
class AndroidLocationSource(
    private val context: Context,
    private val clock: Clock,
    private val manager: LocationManager = context.getSystemService(LocationManager::class.java),
    private val providers: () -> List<String> = { providersFor(manager) },
) : LocationSource {
    override fun permission(): Permission = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Permission.PRECISE
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> Permission.APPROXIMATE
        else -> Permission.NONE
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnown(): Fix? {
        if (permission() == Permission.NONE) return null
        return providers()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
            ?.let(::fix)
    }

    @SuppressLint("MissingPermission")
    override fun updates(): Flow<Fix> = callbackFlow {
        if (permission() == Permission.NONE) {
            close()
            return@callbackFlow
        }
        val listener = LocationListenerCompat { trySend(fix(it)) }
        val request = LocationRequestCompat.Builder(INTERVAL_MILLIS).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
        // A provider that is off, or a permission taken back meanwhile, only means fewer positions.
        providers().forEach { provider ->
            runCatching { LocationManagerCompat.requestLocationUpdates(manager, provider, request, Executor { it.run() }, listener) }
        }
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    /** Its time comes from the age Android gives it, which does not move when the phone's clock is changed. */
    private fun fix(location: Location): Fix = Fix(
        lat = location.latitude,
        lng = location.longitude,
        accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
        timeMillis = clock.millis() - (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000,
    )

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val INTERVAL_MILLIS = 1_000L
    }
}

/** The fused provider on Android 12 and later when the phone has it; otherwise GPS and network together. */
fun providersFor(manager: LocationManager, sdk: Int = Build.VERSION.SDK_INT): List<String> =
    if (sdk >= Build.VERSION_CODES.S && LocationManagerCompat.hasProvider(manager, FUSED)) {
        listOf(FUSED)
    } else {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in manager.allProviders }
    }

/** `LocationManager.FUSED_PROVIDER`, which the SDK names only from Android 12. */
private const val FUSED = "fused"
