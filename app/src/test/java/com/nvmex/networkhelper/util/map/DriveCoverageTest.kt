package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import org.junit.Assert.*
import org.junit.Test

class DriveCoverageTest {
    private val fixes = (0..6).map { DriveTrackFix((it + 1) * 1000L, 31.0 + it * 0.0001, 121.0) }
    private val states = listOf(41, 41, 78, 41, 41, 28, 28).map { NetworkPanelUiState(cellType = "NR", band = "$it") }

    @Test fun coverageMeasuresMatchedRunsAgainstTheEntireOriginalRoute() {
        val filter = DriveBandFilter(nr = setOf(41))
        val coverage = driveTrackCoverage(fixes) { filter.matches(states[it]) }
        assertEquals(66.72, coverage.totalMeters, 0.1)
        assertEquals(22.24, coverage.coveredMeters, 0.1)
        assertEquals(1.0 / 3, coverage.fraction!!, 0.00001)
        // The denominator is not shortened to the selected points or a straight shortcut.
        assertEquals(driveTrackLength(fixes), coverage.totalMeters, 0.00001)
    }

    @Test fun multipleSelectedBandsShareOneCoverageDistanceWithoutDoubleCounting() {
        val filter = DriveBandFilter(nr = setOf(41, 28))
        val coverage = driveTrackCoverage(fixes) { filter.matches(states[it]) }
        assertEquals(2.0 / 3, coverage.fraction!!, 0.00001)
        assertEquals(1.0, driveTrackCoverage(fixes) { true }.fraction!!, 0.0)
        assertEquals(0.0, driveTrackCoverage(fixes) { false }.fraction!!, 0.0)
    }

    @Test fun rejectsResumeGapsGpsJumpsInvalidPositionsAndLongOutages() {
        val route = listOf(
            DriveTrackFix(1000, 31.0, 121.0), DriveTrackFix(2000, 31.0001, 121.0),
            DriveTrackFix(3000, 31.001, 121.0, segmentStart = true), DriveTrackFix(4000, 31.0011, 121.0),
            DriveTrackFix(5000, 35.0, 121.0), DriveTrackFix(6000, Double.NaN, 121.0),
            DriveTrackFix(7000, 35.0, 121.0), DriveTrackFix(8000, 35.0001, 121.0),
            DriveTrackFix(200000, 35.0002, 121.0), DriveTrackFix(201000, 35.0003, 121.0))
        val coverage = driveTrackCoverage(route) { true }
        assertEquals(44.48, coverage.totalMeters, 0.1)
        assertEquals(driveTrackLength(route), coverage.totalMeters, 0.00001)
        assertEquals(coverage.totalMeters, coverage.coveredMeters, 0.0)
    }

    @Test fun isolatedMatchesDoNotBridgeHiddenBandsAndJitterHasNoPercentage() {
        assertEquals(0.0, driveTrackCoverage(fixes) { it % 2 == 0 }.coveredMeters, 0.0)
        val jitter = listOf(DriveTrackFix(1000, 31.0, 121.0), DriveTrackFix(2000, 31.000001, 121.0))
        assertNull(driveTrackCoverage(jitter) { true }.fraction)
        assertNull(driveTrackCoverage(emptyList()) { true }.fraction)
        assertNull(driveTrackCoverage(fixes.take(1)) { true }.fraction)
    }

    @Test fun selectedSimUsesItsOwnNetworkStatesForTheSameSharedGpsRoute() {
        val filter = DriveBandFilter(nr = setOf(41))
        val sim1 = states
        val sim2 = states.map { it.copy(cellType = "LTE", band = "41") }
        assertEquals(1.0 / 3, driveTrackCoverage(fixes) { filter.matches(sim1[it]) }.fraction!!, 0.00001)
        assertEquals(0.0, driveTrackCoverage(fixes) { filter.matches(sim2[it]) }.fraction!!, 0.0)
    }
}
