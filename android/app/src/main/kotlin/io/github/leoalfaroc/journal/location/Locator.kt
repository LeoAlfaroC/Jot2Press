package io.github.leoalfaroc.journal.location

import io.github.leoalfaroc.journal.data.Entry
import io.github.leoalfaroc.journal.data.EntryState
import io.github.leoalfaroc.journal.data.Queue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/** A position chosen at `Send`. A live one came while the dialog listened; the other is the system's last known position. */
data class Pick(val fix: Fix, val live: Boolean)

/**
 * Listens for positions while the capture dialog wants them, and goes on for entries sent with an imprecise position
 * or none, while they are in their grace window. One stream of positions serves the dialog and every such entry.
 */
class Locator(
    private val source: LocationSource,
    private val queue: Queue,
    private val service: SearchService,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Lets a search end on time when no position comes. */
    private val ticks: Flow<Unit> = everySecond(),
) {
    private class Search(val endsAt: Long, var held: Pick?) {
        /** A live position replaces a last known one; otherwise only a more accurate one does. */
        fun improvedBy(fix: Fix): Boolean = held.let { it == null || !it.live || fix.accuracy < it.fix.accuracy }
    }

    private val mutex = Mutex()
    /** The dialogs that want positions. */
    private val owners = mutableSetOf<Any>()
    private var best: Fix? = null
    private val searches = mutableMapOf<String, Search>()
    private var stream: Job? = null
    private var serviceOn = false

    /** How many positions and ticks have been handled. Tests wait on it. */
    internal val handled = MutableStateFlow(0)

    fun permission(): Permission = source.permission()

    /** A new dialog: the last one's best position is not this one's. */
    suspend fun opened() = mutex.withLock { best = null }

    /**
     * Whether `owner`, a dialog, wants positions: in sight, with the pin shown and on. Listening belongs to the dialog
     * that asked for it, because Android may stop a closing dialog long after the next one has started.
     */
    suspend fun listen(owner: Any, on: Boolean) = mutex.withLock {
        if (on) owners.add(owner) else owners.remove(owner)
        settle()
    }

    /** The position an entry takes at `Send`: the best since the dialog opened, or else a last known one at most 2 minutes old. */
    suspend fun pick(): Pick? {
        mutex.withLock { best }?.let { return Pick(it, live = true) }
        val last = source.lastKnown() ?: return null
        return if (clock.millis() - last.timeMillis <= LAST_KNOWN_MILLIS) Pick(last, live = false) else null
    }

    /**
     * Goes on searching for `entry`, just sent with `pick`, when a better position can still make a difference.
     * Called before the dialog closes: Android lets the service start only while the dialog is in sight.
     */
    suspend fun follow(entry: Entry, pick: Pick?) = mutex.withLock {
        val graceEndsAt = entry.graceEndsAt
        if (!entry.attachLocation || entry.state != EntryState.GRACE || graceEndsAt == null) return@withLock
        if (pick != null && pick.fix.accuracy <= PRECISE_METRES) return@withLock
        // An approximate position never comes within 20 m.
        if (source.permission() != Permission.PRECISE) return@withLock
        if (!serviceOn) {
            if (!service.start()) return@withLock
            serviceOn = true
        }
        searches[entry.id] = Search(minOf(graceEndsAt, clock.millis() + SEARCH_MILLIS), pick)
        settle()
    }

    /** A position, or a tick when `fix` is null. Runs under the lock. */
    private suspend fun step(fix: Fix?) {
        if (fix != null && owners.isNotEmpty() && fix.accuracy < (best?.accuracy ?: Float.MAX_VALUE)) best = fix
        val now = clock.millis()
        for ((id, search) in searches.toList()) {
            val done = when {
                now >= search.endsAt || !queue.stillInGrace(id) -> true
                fix == null || !search.improvedBy(fix) -> false
                // The entry left its grace window meanwhile: nothing was written.
                !queue.placeLocation(id, fix) -> true
                else -> {
                    search.held = Pick(fix, live = true)
                    fix.accuracy <= PRECISE_METRES
                }
            }
            if (done) searches.remove(id)
        }
        settle()
    }

    /** Listens while anything wants positions. The service stops when no entry is searched for and no dialog listens. */
    private fun settle() {
        val wanted = owners.isNotEmpty() || searches.isNotEmpty()
        if (wanted && stream == null) {
            stream = scope.launch {
                merge<Fix?>(source.updates(), ticks.map { null }).collect { fix ->
                    mutex.withLock { step(fix) }
                    handled.update { it + 1 }
                }
            }
        }
        if (!wanted) {
            stream?.cancel()
            stream = null
        }
        if (serviceOn && searches.isEmpty() && owners.isEmpty()) {
            service.stop()
            serviceOn = false
        }
    }

    companion object {
        /** A position this close needs no better one. */
        const val PRECISE_METRES = 20f
        const val SEARCH_MILLIS = 60_000L
        const val LAST_KNOWN_MILLIS = 120_000L
    }
}

/** A tick each second, for as long as it is collected. */
fun everySecond(): Flow<Unit> = flow {
    while (true) {
        delay(1_000)
        emit(Unit)
    }
}
