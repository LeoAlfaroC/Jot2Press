package io.github.leoalfaroc.jot2press.capture

import java.util.concurrent.atomic.AtomicInteger

/** How many capture dialogs are in sight. A count, not a flag: a closing dialog may be stopped after the next one has started. */
class Dialogs {
    private val started = AtomicInteger()

    fun started() {
        started.incrementAndGet()
    }

    fun stopped() {
        started.decrementAndGet()
    }

    val onScreen: Boolean get() = started.get() > 0
}
