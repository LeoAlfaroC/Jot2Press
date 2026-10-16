package io.github.leoalfaroc.journal.location

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
