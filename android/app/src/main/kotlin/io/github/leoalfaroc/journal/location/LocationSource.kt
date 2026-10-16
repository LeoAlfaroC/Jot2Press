package io.github.leoalfaroc.journal.location

import kotlinx.coroutines.flow.Flow

/** What the user allowed the app. An approximate position never comes within 20 m. */
enum class Permission { NONE, APPROXIMATE, PRECISE }

/** The phone's position, as Android's location API gives it. */
interface LocationSource {
    /** Read each time: the user can change it in Android settings at any moment. */
    fun permission(): Permission

    /** The newest position the system already has, from the providers this source uses. Null without a permission. */
    suspend fun lastKnown(): Fix?

    /** Current positions, at high accuracy about once a second, for as long as it is collected. Ends at once without a permission. */
    fun updates(): Flow<Fix>
}
