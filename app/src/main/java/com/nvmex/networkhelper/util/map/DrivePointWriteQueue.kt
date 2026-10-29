package com.nvmex.networkhelper.util.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Enqueue on the main thread; drain before offering save/discard or deleting a session. */
internal class DrivePointWriteQueue<T>(
    private val scope: CoroutineScope,
    private val write: suspend (List<T>, String) -> Unit
) {
    private var tail: Job? = null
    @Volatile private var failure: Throwable? = null

    fun enqueue(points: List<T>, sessionId: String) {
        val previous = tail
        tail = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                write(points, sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                driveLog("persist failed session=$sessionId rows=${points.size} error=${error.javaClass.simpleName}:${error.message}")
            }
        }
    }

    suspend fun awaitIdle(): Throwable? {
        tail?.join()
        return failure.also { failure = null }
    }
}
