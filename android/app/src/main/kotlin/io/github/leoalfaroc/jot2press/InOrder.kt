package io.github.leoalfaroc.jot2press

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs pieces of work one at a time, in the order they were handed over, each to its end before the next begins.
 * Pieces launched on their own keep no such order: the one launched second can get to the queue first.
 * A piece that throws is handed to [onFailure], and the next one runs: one failure must not stop every later save.
 */
class InOrder(scope: CoroutineScope, private val onFailure: (Throwable) -> Unit = {}) {
    private val pieces = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (piece in pieces) {
                try {
                    piece()
                } catch (e: CancellationException) {
                    // Only the scope ending stops the line; a piece cancelled on its own, by a timeout say, does not.
                    if (!currentCoroutineContext().isActive) throw e
                    onFailure(e)
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
        }
    }

    fun run(piece: suspend () -> Unit) {
        pieces.trySend(piece)
    }
}
