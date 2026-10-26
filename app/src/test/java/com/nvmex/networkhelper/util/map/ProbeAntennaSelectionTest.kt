package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem
import org.junit.Assert.*
import org.junit.Test

class ProbeAntennaSelectionTest {
    private val a = ProbedStation("a", lte = listOf(LteCellParam(id = 1, earfcn = 1650)))
    private val b = ProbedStation("b", lte = listOf(LteCellParam(id = 2, earfcn = 38950)))
    private val unfiltered = StationExclusion()

    @Test fun selectingAnotherStationClosesOnlyTheUnpinnedAntenna() {
        val selection = ProbeAntennaSelection()
        assertTrue(selection.select(a))
        assertFalse(selection.select(a))
        selection.select(b)
        assertEquals(b.lte, selection.displayed(unfiltered).first)
        selection.select(null)
        assertTrue(selection.displayed(unfiltered).first.isEmpty())
    }

    @Test fun pinsSurviveSelectionChangesAndClearOnlyOnUnpinOrComponentDestruction() {
        val selection = ProbeAntennaSelection()
        selection.select(a)
        assertTrue(selection.togglePin(a))
        assertEquals(a.lte, selection.displayed(unfiltered).first)
        selection.select(b)
        assertEquals(a.lte + b.lte, selection.displayed(unfiltered).first)
        selection.select(null)
        assertEquals(a.lte, selection.displayed(unfiltered).first)
        assertFalse(selection.togglePin(a))
        assertTrue(selection.displayed(unfiltered).first.isEmpty())
        selection.togglePin(a)
        selection.select(b)
        selection.clear()
        assertFalse(selection.isPinned(a))
        assertTrue(selection.displayed(unfiltered).first.isEmpty())
    }

    @Test fun exclusionHidesPinnedLteWithoutLosingPinsOrAffectingNr() {
        val selection = ProbeAntennaSelection()
        val nr = ProbedStation("nr", nr = listOf(NrCellParamItem(id = 3, nr_arfcn = 520000)))
        selection.togglePin(a)
        selection.togglePin(nr)
        val filtered = selection.displayed(StationExclusion(bands = setOf(3)))
        assertTrue(filtered.first.isEmpty())
        assertEquals(nr.nr, filtered.second)
        assertTrue(selection.isPinned(a))
        assertEquals(a.lte, selection.displayed(unfiltered).first)
    }
}
