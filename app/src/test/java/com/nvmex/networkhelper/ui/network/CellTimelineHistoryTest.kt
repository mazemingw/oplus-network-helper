package com.nvmex.networkhelper.ui.network

import com.nvmex.networkhelper.model.network.CellTimelinePoint
import com.nvmex.networkhelper.model.network.upsertCellTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

class CellTimelineHistoryTest {
    @Test fun returningToKnownCellMovesItToEndWithoutDuplicate() {
        val b39 = point(ts = 1, pci = "20", ci = "126004493", band = "39")
        val b3 = point(ts = 2, pci = "168", ci = "126004533", band = "3")
        val returnedB39 = b39.copy(ts = 3, rsrp = "-85")

        val result = upsertCellTimeline(
            upsertCellTimeline(upsertCellTimeline(emptyList(), b39), b3),
            returnedB39
        )

        assertEquals(listOf(b3, returnedB39), result)
        assertEquals(1, result.count { it.sameCell(b39) })
        assertEquals(3L, result.last().ts)
    }

    @Test fun keepsOnlyMostRecentUniqueCells() {
        val points = (1..4).fold(emptyList<CellTimelinePoint>()) { history, value ->
            upsertCellTimeline(history, point(value.toLong(), value.toString(), value.toString(), value.toString()), limit = 3)
        }
        assertEquals(listOf("2", "3", "4"), points.map { it.pci })
    }

    private fun point(ts: Long, pci: String, ci: String, band: String) = CellTimelinePoint(
        ts = ts,
        cellType = "LTE",
        tac = "30751",
        pci = pci,
        ci = ci,
        band = band,
        rsrp = "-90",
        rsrq = "-10",
        sinr = "10"
    )
}
