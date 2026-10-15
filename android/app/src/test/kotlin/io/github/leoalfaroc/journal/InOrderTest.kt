package io.github.leoalfaroc.journal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
}
