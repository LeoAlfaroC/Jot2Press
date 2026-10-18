package io.github.leoalfaroc.jot2press

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs pieces of work one at a time, in the order they were handed over, each to its end before the next begins.
 * Pieces launched on their own keep no such order: the one launched second can get to the queue first.
 */
class InOrder(scope: CoroutineScope) {
    private val pieces = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (piece in pieces) piece() }
    }

    fun run(piece: suspend () -> Unit) {
        pieces.trySend(piece)
    }
}
