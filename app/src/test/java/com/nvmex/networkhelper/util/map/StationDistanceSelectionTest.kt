package com.nvmex.networkhelper.util.map

import com.amap.api.maps.model.LatLng
import org.junit.Assert.*
import org.junit.Test

class StationDistanceSelectionTest {
    private val fix = LatLng(22.8, 108.3)
    private val a = LatLng(22.81, 108.31)
    private val b = LatLng(22.82, 108.32)

    @Test fun transientLocationLossKeepsTheSelectedLineOrigin() {
        val selection = StationDistanceSelection()
        selection.updatePosition(fix)
        selection.select(a)
        repeat(10) { selection.updatePosition(null) }
        assertEquals(fix, selection.origin)
        assertEquals(a, selection.station)
    }

    @Test fun anotherStationCanBeSelectedDuringALocationGap() {
        val selection = StationDistanceSelection()
        selection.updatePosition(fix)
        selection.select(a)
        selection.updatePosition(null)
        selection.select(b)
        assertEquals(fix, selection.origin)
        assertEquals(b, selection.station)
    }

    @Test fun selectionBeforeFirstFixWaitsWithoutInventingCoordinates() {
        val selection = StationDistanceSelection()
        selection.select(a)
        selection.updatePosition(null)
        assertNull(selection.origin)
        assertEquals(a, selection.station)
        selection.updatePosition(fix)
        assertEquals(fix, selection.origin)
    }

    @Test fun newValidFixMovesTheOriginWithoutClearingTheStation() {
        val selection = StationDistanceSelection()
        selection.updatePosition(fix)
        selection.select(a)
        val next = LatLng(22.805, 108.305)
        selection.updatePosition(next)
        assertEquals(next, selection.origin)
        assertEquals(a, selection.station)
    }

    @Test fun deliberateClearRemovesOnlyTheTarget() {
        val selection = StationDistanceSelection()
        selection.updatePosition(fix)
        selection.select(a)
        selection.clear()
        assertNull(selection.station)
        selection.select(b)
        assertEquals(fix, selection.origin)
        assertEquals(b, selection.station)
    }

    @Test fun invalidFixDoesNotReplaceTheLastKnownValidOrigin() {
        val selection = StationDistanceSelection()
        selection.updatePosition(fix)
        selection.updatePosition(LatLng(Double.NaN, 108.3))
        assertEquals(fix, selection.origin)
    }
}
