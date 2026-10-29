package com.nvmex.networkhelper.util.qmi

import com.nvmex.networkhelper.model.network.CellTimelinePoint
import org.junit.Assert.*
import org.junit.Test

class QmiStablePresentationTest {
    private fun cell(pci: Int, rsrp: String = "-90") = QmiTimelineNeighbor(
        CellTimelinePoint(1000, "NR", "—", pci.toString(), "", "78", rsrp, "—", "—", "640000"))
    private val a = cell(1)
    private val b = cell(2)

    @Test fun noUsableNeighborsImmediatelyLeavesTheHistoryFallbackAvailable() {
        val hold = QmiNeighborDisplayHold()
        assertTrue(hold.update(emptyList(), 0).isEmpty())
        assertEquals(0L, hold.remainingMs(0))
    }

    @Test fun aVisibleNeighborCannotDisappearBeforeThreeSeconds() {
        val hold = QmiNeighborDisplayHold()
        assertEquals(listOf(a), hold.update(listOf(a), 0))
        assertEquals(listOf(a), hold.update(emptyList(), 1000))
        assertEquals(listOf(a), hold.update(emptyList(), 2999))
        assertTrue(hold.update(emptyList(), 3000).isEmpty())
    }

    @Test fun replacementReceivesItsOwnFullDisplayWindow() {
        val hold = QmiNeighborDisplayHold()
        hold.update(listOf(a), 0)
        assertEquals(listOf(a), hold.update(listOf(b), 2000))
        assertEquals(listOf(b), hold.update(listOf(b), 3000))
        assertEquals(listOf(b), hold.update(emptyList(), 5999))
        assertTrue(hold.update(emptyList(), 6000).isEmpty())
    }

    @Test fun rankingAndMeasurementsStayFixedDuringTheClickWindow() {
        val hold = QmiNeighborDisplayHold()
        val original = listOf(a, b)
        val changed = listOf(cell(2, "-70"), cell(1, "-100"))
        hold.update(original, 0)
        assertEquals(original, hold.update(changed, 2999))
        assertEquals(changed, hold.update(changed, 3000))
    }

    @Test fun freshMeasurementsOfTheSameCellDoNotExtendAnExpiredWindowForever() {
        val hold = QmiNeighborDisplayHold()
        hold.update(listOf(a), 0)
        val updated = cell(1, "-80")
        assertEquals(listOf(updated), hold.update(listOf(updated), 3000))
        assertTrue(hold.update(emptyList(), 3100).isEmpty())
    }

    @Test fun expandedRowsReceiveThreeSecondsFromTheExpansion() {
        val hold = QmiNeighborDisplayHold()
        hold.update(listOf(a, b), 0)
        hold.retainForInteraction(2500)
        assertEquals(listOf(a, b), hold.update(emptyList(), 5499))
        assertTrue(hold.update(emptyList(), 5500).isEmpty())
    }

    @Test fun scrollingFreezesTheBatchAndItsEndStartsAnotherClickWindow() {
        val hold = QmiNeighborDisplayHold()
        hold.update(listOf(a), 0)
        assertEquals(listOf(a), hold.update(listOf(b), 1000, interacting = true))
        assertEquals(listOf(a), hold.update(listOf(b), 9000, interacting = true))
        assertEquals(listOf(a), hold.update(listOf(b), 9500))
        assertEquals(listOf(a), hold.update(listOf(b), 12499))
        assertEquals(listOf(b), hold.update(listOf(b), 12500))
    }

    @Test fun aNewSimOrPlaybackOwnerDoesNotInheritHeldNeighborRows() {
        val firstSim = QmiNeighborDisplayHold()
        firstSim.update(listOf(a), 0)
        val secondSim = QmiNeighborDisplayHold()
        assertTrue(secondSim.update(emptyList(), 1000).isEmpty())
        assertEquals(listOf(a), firstSim.update(emptyList(), 1000))
    }

    private fun chain(index: Int) = QmiRxChain(index, true, -90.0 - index, -100.0 - index, null, null, 30.0 * index)

    @Test fun twoAndFourChainReportsHaveTheSameSlotsWithoutInventedSamples() {
        val two = listOf(chain(0), chain(1))
        val four = (0..3).map(::chain)
        val twoSlots = qmiRxSlots(two)
        assertEquals(listOf(0, 1, 2, 3), twoSlots.map { it.index })
        assertEquals(twoSlots.map { it.index }, qmiRxSlots(four).map { it.index })
        assertNull(twoSlots[2].measurement); assertNull(twoSlots[3].measurement)
        assertSame(two[0], twoSlots[0].measurement)
        assertEquals(-102.0, qmiRxSlots(four)[2].measurement!!.rsrp!!, 0.001)
        assertNull(qmiRxSlots(two)[2].measurement)
    }

    @Test fun slotOrderingDoesNotFollowTheIncomingArrayOrUnknownExtraIndices() {
        val slots = qmiRxSlots(listOf(chain(3), chain(0), chain(99)))
        assertEquals(listOf(0, 1, 2, 3), slots.map { it.index })
        assertEquals(0, slots[0].measurement!!.index); assertEquals(3, slots[3].measurement!!.index)
        assertNull(slots[1].measurement); assertNull(slots[2].measurement)
    }

    @Test fun aDisappearingNrGroupRetainsItsPlaceButNoneOfItsOldReadings() {
        val lte = QmiRxGroup("LTE", listOf(chain(0)))
        val nr = QmiRxGroup("NR", (0..3).map(::chain))
        val first = qmiRxDisplayGroups(listOf(lte, nr), emptyList())
        val next = qmiRxDisplayGroups(listOf(lte), first.map { it.rat })
        assertEquals(listOf("LTE", "NR"), next.map { it.rat })
        assertSame(lte, next[0]); assertTrue(next[1].chains.isEmpty())
        assertTrue(qmiRxSlots(next[1].chains).all { it.measurement == null })
    }

    @Test fun aNewExpansionOrSimCanRecomputeItsRatLayout() {
        val lte = QmiRxGroup("LTE", listOf(chain(0)))
        assertEquals(listOf("LTE"), qmiRxDisplayGroups(listOf(lte), emptyList()).map { it.rat })
        assertTrue(qmiRxDisplayGroups(emptyList(), emptyList()).isEmpty())
        assertTrue(qmiRxDisplayGroups(emptyList(), listOf("NR")).single().chains.isEmpty())
    }
}
