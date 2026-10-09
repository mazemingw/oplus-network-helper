package com.nvmex.networkhelper.util.network

import com.nvmex.networkhelper.model.network.ModemQosInfo.Source
import org.junit.Assert.*
import org.junit.Test

class ModemQosNetworkTrackerTest {
    @Test fun switchingNrToLteInvalidatesImmediatelyAndReconnectsOnlyAfterSettling() {
        val tracker = ModemQosNetworkTracker()
        tracker.reset(mapOf(1 to Source.NR_5GSM, 5 to Source.NR_5GSM), 0)
        val lte = mapOf(1 to Source.LTE_ESM, 5 to Source.NR_5GSM)
        assertEquals(setOf(1), tracker.observe(lte, 100).invalidated)
        assertTrue(tracker.observe(lte, 1_000).stabilized.isEmpty())
        assertEquals(setOf(1), tracker.observe(lte, 2_100).stabilized)
        assertTrue(tracker.observe(lte, 5_000).stabilized.isEmpty())
    }

    @Test fun unknownDoesNotRestoreOldResultsAndReturningToSameRatReconnects() {
        val tracker = ModemQosNetworkTracker()
        tracker.reset(mapOf(1 to Source.NR_5GSM), 0)
        assertEquals(setOf(1), tracker.observe(mapOf(1 to null), 100).invalidated)
        assertTrue(tracker.observe(mapOf(1 to null), 5_000).stabilized.isEmpty())
        assertEquals(setOf(1), tracker.observe(mapOf(1 to Source.NR_5GSM), 5_100).invalidated)
        assertEquals(setOf(1), tracker.observe(mapOf(1 to Source.NR_5GSM), 7_100).stabilized)
    }

    @Test fun flappingRestartsTheSettleWindowWithoutRepeatedReconnects() {
        val tracker = ModemQosNetworkTracker()
        tracker.reset(mapOf(1 to Source.NR_5GSM), 0)
        tracker.observe(mapOf(1 to Source.LTE_ESM), 100)
        tracker.observe(mapOf(1 to Source.NR_5GSM), 1_000)
        tracker.observe(mapOf(1 to Source.LTE_ESM), 1_900)
        assertTrue(tracker.observe(mapOf(1 to Source.LTE_ESM), 3_000).stabilized.isEmpty())
        assertEquals(setOf(1), tracker.observe(mapOf(1 to Source.LTE_ESM), 3_900).stabilized)
    }

    @Test fun removingSimInvalidatesOnlyThatSubscription() {
        val tracker = ModemQosNetworkTracker()
        tracker.reset(mapOf(1 to Source.LTE_ESM, 5 to Source.NR_5GSM), 0)
        assertEquals(setOf(5), tracker.observe(mapOf(1 to Source.LTE_ESM), 100).invalidated)
        assertTrue(tracker.observe(mapOf(1 to Source.LTE_ESM), 3_000).invalidated.isEmpty())
    }

    @Test fun ourOwnRadioResetSeedsNewSourcesWithoutClearingFreshResultsAgain() {
        val tracker = ModemQosNetworkTracker()
        tracker.reset(mapOf(1 to Source.NR_5GSM), 0)
        tracker.reset(mapOf(1 to Source.LTE_ESM), 5_000)
        val result = tracker.observe(mapOf(1 to Source.LTE_ESM), 8_000)
        assertTrue(result.invalidated.isEmpty())
        assertTrue(result.stabilized.isEmpty())
    }
}
