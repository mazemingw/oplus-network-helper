package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSnapshotsTest {
    @Test
    fun mapsSubscriptionsToBothPhysicalSlotsWithoutSharingSnapshots() {
        val sim1 = NetworkPanelUiState(subId = 1, cellType = "LTE", rsrp = "-99 dBm", ci = "17064599")
        val sim2 = NetworkPanelUiState(subId = 5, cellType = "NR", ssRsrp = "-108 dBm", ci = "48063443200")
        val result = driveSnapshotsBySlot(mapOf(1 to sim1, 5 to sim2), listOf(1 to 0, 5 to 1))
        assertEquals(setOf(0, 1), result.keys)
        assertSame(sim1, result[0])
        assertSame(sim2, result[1])
    }

    @Test
    fun doesNotCreateSim1PointsOrCopyAnotherSubscriptionWhenMissing() {
        assertTrue(driveSnapshotsBySlot(emptyMap(), listOf(1 to 0, 5 to 1)).isEmpty())
        val wrongSub = NetworkPanelUiState(subId = 1)
        assertTrue(driveSnapshotsBySlot(mapOf(5 to wrongSub), listOf(5 to 1)).isEmpty())
    }
}
