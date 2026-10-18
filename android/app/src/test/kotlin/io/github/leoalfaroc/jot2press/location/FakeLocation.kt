package io.github.leoalfaroc.jot2press.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Positions come only when a test hands them over. No permission until a test grants one. */
class FakeLocationSource : LocationSource {
    @Volatile var permission: Permission = Permission.NONE

    /** What `lastKnown` answers. */
    @Volatile var last: Fix? = null

    private val positions = MutableSharedFlow<Fix>()

    /** How many collect `updates` at the moment. */
    val subscribers: StateFlow<Int> get() = positions.subscriptionCount

    override fun permission(): Permission = permission
    override suspend fun lastKnown(): Fix? = last
    override fun updates(): Flow<Fix> = positions

    /** Hands a position to whoever collects, once someone does. */
    suspend fun emit(fix: Fix) {
        positions.subscriptionCount.first { it > 0 }
        positions.emit(fix)
    }
}

/** Starts unless told to refuse, as Android does when the app is not in the foreground. */
class FakeSearchService : SearchService {
    @Volatile var refuse = false

    @Volatile var running = false
        private set

    @Volatile var starts = 0
        private set

    override fun start(): Boolean {
        if (refuse) return false
        starts++
        running = true
        return true
    }

    override fun stop() {
        running = false
    }
}
