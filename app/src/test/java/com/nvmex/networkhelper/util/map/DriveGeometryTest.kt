package com.nvmex.networkhelper.util.map

import org.junit.Assert.*
import org.junit.Test

class DriveGeometryTest {
    @Test fun countsBothSimsOnceAndExcludesResumeGapsAndGpsTeleports() {
        val a = DriveTrackFix(1000, 31.0, 121.0)
        val b = DriveTrackFix(4000, 31.0001, 121.0)
        val resume = DriveTrackFix(5000, 31.001, 121.0, segmentStart = true)
        val next = DriveTrackFix(8000, 31.0011, 121.0)
        val teleport = DriveTrackFix(9000, 35.0, 121.0)
        assertEquals(22.24, driveTrackLength(listOf(a, a, b, b, resume, resume, next, teleport)), 0.1)
    }

    @Test fun lowZoomUsesDenseSpacingInsteadOfTheOldHundredPointBudget() {
        val dots = (0..1999).map { DriveDot(it, 31.0 + it * 0.0005, 121.0, false) }
        val selected = selectDriveDots(dots, 10f, true, 1f)
        assertTrue(selected.size > 200)
        assertEquals(0, selected.first())
        assertEquals(1999, selected.last())
        assertTrue(selected.size <= 4000)
        // Each retained marker lies at most one source step beyond the 4px spacing target.
        assertTrue(selected.zipWithNext().maxOf { (a, b) -> b - a } <= 11)
    }

    @Test fun boundedMarkersPreserveBothEndsAndCoverTheWholeRoute() {
        val dots = (0..19999).map { DriveDot(it, 31.0 + it * 0.00001, 121.0, it % 5 == 0) }
        val selected = selectDriveDots(dots, 19f, true, 1f, maxDots = 4000)
        assertEquals(4000, selected.size)
        assertEquals(0, selected.first())
        assertEquals(19999, selected.last())
        assertTrue(selected.zipWithNext().maxOf { (a, b) -> b - a } < 30)
        assertEquals(listOf(0, 19999), selectDriveDots(dots, 19f, true, 1f, maxDots = 2))
    }

    @Test fun revisitingTheSameScreenCellsKeepsRouteOrderAndHandovers() {
        val dots = (0..1000).map {
            DriveDot(it, 31.0 + (if (it <= 500) it else 1000 - it) * 0.0001, 121.0, it == 500)
        }
        val selected = selectDriveDots(dots, 14f, true, 1f)
        assertTrue(500 in selected)
        assertTrue(selected.any { it in 1..499 })
        assertTrue(selected.any { it in 501..999 })
        assertEquals(1000, selected.last())
    }

    @Test fun turningOffHandoverDisplayDoesNotChangeTheContinuousTrack() {
        val dots = listOf(DriveDot(0, 31.0, 121.0, false), DriveDot(1, 31.0001, 121.0, true),
            DriveDot(2, 31.0002, 121.0, false))
        assertTrue(1 in selectDriveDots(dots, 18f, true, 1f))
        assertFalse(1 in selectDriveDots(dots, 18f, false, 1f))
        val fixes = dots.map { DriveTrackFix(it.id * 1000L, it.lat, it.lng) }
        assertEquals(listOf(listOf(0, 1, 2)), driveTrackSegments(fixes))
    }

    @Test fun fullResolutionLineKeepsEveryCurvePointEvenWhenMarkersAreThinned() {
        val fixes = (0..9999).map { DriveTrackFix(it * 1000L, 31.0 + it * 0.000003, 121.0) }
        val segments = driveTrackSegments(fixes)
        assertEquals(1, segments.size)
        assertEquals(fixes.indices.toList(), segments.single())
        val dots = fixes.mapIndexed { i, p -> DriveDot(i, p.lat!!, p.lng!!, false) }
        assertTrue(selectDriveDots(dots, 10f, true, 1f).size < fixes.size)
        assertEquals(10000, fixes.size)
    }

    @Test fun neverDrawsLinesAcrossResumeGapsInvalidCoordinatesOrGpsJumps() {
        val fixes = listOf(
            DriveTrackFix(1000, 31.0, 121.0), DriveTrackFix(2000, 31.0001, 121.0),
            DriveTrackFix(3000, 31.001, 121.0, segmentStart = true), DriveTrackFix(4000, 31.0011, 121.0),
            DriveTrackFix(5000, 35.0, 121.0), DriveTrackFix(6000, Double.NaN, 121.0),
            DriveTrackFix(7000, 35.0, 121.0), DriveTrackFix(8000, 35.0001, 121.0),
            DriveTrackFix(200000, 35.0002, 121.0), DriveTrackFix(201000, 35.0003, 121.0)
        )
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(6, 7), listOf(8, 9)), driveTrackSegments(fixes))
    }
}
