package com.nvmex.networkhelper.util.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DrivePointWriteQueueTest {
    @Test
    fun waitsForBothSimGroupsBeforeSaveOrDiscardAndKeepsSessionIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val written = mutableListOf<Pair<String, List<Int>>>()
            val queue = DrivePointWriteQueue<Int>(scope) { points, session ->
                if (written.isEmpty()) {
                    entered.complete(Unit)
                    release.await()
                }
                written.add(session to points)
            }
            queue.enqueue(listOf(0, 1), "first-session")
            entered.await()
            queue.enqueue(listOf(10, 11), "second-session")
            val drain = async { queue.awaitIdle() }
            assertFalse(drain.isCompleted)
            assertTrue(written.isEmpty())
            release.complete(Unit)
            assertNull(drain.await())
            assertEquals(listOf("first-session" to listOf(0, 1), "second-session" to listOf(10, 11)), written)
        } finally { scope.cancel() }
    }

    @Test
    fun reportsWriteFailureWhileAllowingLaterWholeGroupsToComplete() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val error = IllegalStateException("disk full")
            val written = mutableListOf<List<Int>>()
            val queue = DrivePointWriteQueue<Int>(scope) { points, session ->
                if (session == "failed") throw error
                written.add(points)
            }
            queue.enqueue(listOf(0, 1), "failed")
            queue.enqueue(listOf(2, 3), "saved")
            assertSame(error, queue.awaitIdle())
            assertEquals(listOf(listOf(2, 3)), written)
            assertNull(queue.awaitIdle())
        } finally { scope.cancel() }
    }
}
