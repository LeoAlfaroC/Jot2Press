package io.github.leoalfaroc.jot2press

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InOrderTest {
    @Test
    fun `a piece waits for the one handed over before it, however long that one takes`() = runTest {
        val inOrder = InOrder(backgroundScope)
        val slow = CompletableDeferred<Unit>()
        val done = mutableListOf<String>()

        inOrder.run {
            slow.await()
            done += "first"
        }
        inOrder.run { done += "second" }
        runCurrent()
        assertTrue(done.isEmpty())

        slow.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), done)
    }

    @Test
    fun `a piece that throws is reported, and the pieces after it still run`() = runTest {
        val failures = mutableListOf<Throwable>()
        val inOrder = InOrder(backgroundScope) { failures += it }
        val done = mutableListOf<String>()

        inOrder.run { error("The disk is full.") }
        inOrder.run { done += "second" }
        runCurrent()

        assertEquals(listOf("The disk is full."), failures.map { it.message })
        assertEquals(listOf("second"), done)
    }

    @Test
    fun `a piece cancelled on its own does not stop the pieces after it`() = runTest {
        val inOrder = InOrder(backgroundScope)
        val done = mutableListOf<String>()

        inOrder.run { withTimeout(1) { awaitCancellation() } }
        inOrder.run { done += "second" }
        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf("second"), done)
    }
}
