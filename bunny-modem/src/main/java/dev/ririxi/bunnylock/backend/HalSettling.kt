package dev.ririxi.bunnylock.backend

import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Cells
import dev.ririxi.bunnylock.modem.runSuspendCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val HAL_LOCK_SETTLE = 8.seconds
private val HAL_LOCK_RECHECK = 6.seconds
private const val HAL_LOCK_RECHECKS = 2
internal const val BLIND_LOCK_RECHECKS = 8
private const val MAX_MISSED_SCANS = 2

internal class HalSettling(
    private val session: ModemSession,
    private val movesOnlyAfterRestart: () -> Boolean,
) {
    private val current get() = session.current
    private val waitStopped = MutableStateFlow(false)

    fun stopWaiting() {
        waitStopped.value = true
    }

    suspend fun settledCells(
        rechecks: Int = HAL_LOCK_RECHECKS,
        hasStray: suspend (List<Cell>) -> Boolean,
    ): List<Cell>? {
        waitStopped.value = false
        session.update { copy(settling = true) }
        try {
            val cells = settledCellsAfterRestart(rechecks, hasStray)
            when {
                waitStopped.value -> {
                    session.log("Waiting for SIM ${current.sim} after the lock was stopped, so the lock stays unchecked.")
                    return null
                }

                cells === null -> session.log("SIM ${current.sim} sent no new cell list after the lock.")
            }
            return cells
        } finally {
            session.update { copy(settling = false) }
        }
    }

    private suspend fun stoppedDuring(wait: Duration): Boolean = withTimeoutOrNull(wait) { waitStopped.first { it } } !== null

    private suspend fun settledCellsAfterRestart(
        rechecks: Int,
        hasStray: suspend (List<Cell>) -> Boolean,
    ): List<Cell>? {
        val cells = scanUntilSettled(if (movesOnlyAfterRestart()) HAL_LOCK_RECHECKS else rechecks, hasStray)
        if (waitStopped.value || !movesOnlyAfterRestart() || (cells !== null && !hasStray(cells))) return cells
        session.log("The phone didn't move to the locked bands, so SIM ${current.sim}'s radio is restarted to make it move.")
        runSuspendCatching { session.restartSimRadio() }
        return scanUntilSettled(rechecks, hasStray) ?: cells
    }

    // A modem that follows a HAL lock leaves the old cell within seconds, so the wait only grows while hasStray still finds one.
    private suspend fun scanUntilSettled(
        rechecks: Int,
        hasStray: suspend (List<Cell>) -> Boolean,
    ): List<Cell>? {
        if (stoppedDuring(HAL_LOCK_SETTLE)) return null
        var cells = Cells.freshScan(session.context, session.subId())
        var missedScans = if (cells === null) 1 else 0
        repeat(rechecks) {
            if (cells !== null && !hasStray(cells)) return cells
            if (missedScans == MAX_MISSED_SCANS) return cells
            if (stoppedDuring(HAL_LOCK_RECHECK)) return null
            val scanned = Cells.freshScan(session.context, session.subId())
            if (scanned === null) {
                missedScans++
            } else {
                missedScans = 0
                cells = scanned
            }
        }
        return cells
    }
}
