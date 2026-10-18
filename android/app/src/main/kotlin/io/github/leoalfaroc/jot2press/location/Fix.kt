package io.github.leoalfaroc.jot2press.location

/** A position from the phone's location API. */
data class Fix(
    val lat: Double,
    val lng: Double,
    /** The radius, in metres, within which the phone is likely to be. */
    val accuracy: Float,
    /** Epoch milliseconds at which it was taken. */
    val timeMillis: Long,
)
