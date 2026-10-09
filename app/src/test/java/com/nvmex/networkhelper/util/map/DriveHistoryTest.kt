package com.nvmex.networkhelper.util.map

import com.amap.api.maps.model.LatLng
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.junit.Assert.*
import org.junit.Test

class DriveHistoryTest {
    private fun point(slot: Int, time: Long, signal: String) = DrivePoint(
        latLng = LatLng(31.0 + time / 100000.0, 121.0), ts = time, simSlot = slot,
        rsrpDbm = null, isHandover = false, cellKey = "cell-$slot",
        snapshot = NetworkPanelUiState(subId = slot + 1, rsrp = signal)
    )

    @Test
    fun restoresBothDatasetsAndCentersOnTheEarliestPoint() {
        val first = point(1, 100, "-110")
        val sim1 = point(0, 101, "-90")
        val history = DriveHistory(listOf(point(0, 200, "-80"), sim1, first))
        assertSame(first, history.firstPoint)
        assertEquals(listOf(101L, 200L), history.pointsBySlot[0]!!.map { it.ts })
        assertEquals("-90", history.snapshots[0]!!.rsrp)
        assertEquals("-110", history.snapshots[1]!!.rsrp)
        assertEquals(1, history.initialSlot(1))
    }

    @Test
    fun supportsLegacySingleSimRecordsWithoutInventingSecondSimData() {
        val history = DriveHistory(listOf(point(0, 100, "-90")))
        assertEquals(0, history.initialSlot(1))
        assertFalse(history.snapshots.containsKey(1))
        assertEquals(setOf(0), history.pointsBySlot.keys)
    }
}
