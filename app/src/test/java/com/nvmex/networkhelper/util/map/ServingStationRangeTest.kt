package com.nvmex.networkhelper.util.map

import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ServingStationRangeTest {
    private val reference = ServingStationReference(22.8, 108.3)
    private val state = NetworkPanelUiState(cellType = "LTE", ci = "1234", tac = "100", pci = "20", arfcn = "1650")
    private val nearby = LteCellParam(eci = 1234, tac = 100, pci = 20, earfcn = 1650,
        latitude = 22.81, longitude = 108.3)
    private val distant = nearby.copy(latitude = 34.2, longitude = 108.9)

    @Test fun crossProvinceExactIdentifiersAreRejectedForBothRatsButLocalChannelChangesStillWork() {
        assertNull(matchServingLte(state, listOf(distant), reference))
        assertEquals(nearby, matchServingLte(state.copy(arfcn = "1646"), listOf(nearby), reference))
        val nr = NrCellParamItem(gcell_id = "1234", nr_tac = 100, nr_pci = 20, nr_arfcn = 422000,
            latitude = distant.latitude, longitude = distant.longitude)
        assertNull(matchServingNr(state.copy(cellType = "NR", arfcn = "422000"), listOf(nr), reference))
    }

    @Test fun rangeFilteringPrecedesMetadataPreferencesAndNeverSubstitutesAnUnrelatedNearbyCell() {
        val localOldMetadata = nearby.copy(tac = 99, earfcn = 1646)
        assertEquals(localOldMetadata, matchServingLte(state, listOf(distant, localOldMetadata), reference))
        assertNull(matchServingLte(state, listOf(distant, nearby.copy(eci = 9999)), reference))
        assertNull(matchServingLte(state, listOf(nearby, nearby.copy(longitude = 108.31)), reference))
    }

    @Test fun noLocationOrInvalidCoordinatesCannotValidateAStationAndTheGuardIsNotTheProbeRadius() {
        assertNull(matchServingLte(state, listOf(nearby), null))
        assertFalse(servingStationInRange(22.8, 108.3, ServingStationReference(Double.NaN, 108.3)))
        assertFalse(servingStationInRange(null, 108.3, reference))
        assertTrue(servingStationInRange(23.24, 108.3, reference)) // about 49 km
        assertFalse(servingStationInRange(23.26, 108.3, reference)) // about 51 km
        val map = Gcj02.wgs84ToGcj02(reference.latitude, reference.longitude)
        val restored = servingStationReference(map.first, map.second)!!
        assertTrue(driveDistanceMeters(reference.latitude, reference.longitude, restored.latitude, restored.longitude) < 5)
    }

    private suspend fun lookup(local: suspend () -> List<LteCellParam>, site: suspend () -> List<LteCellParam>,
        cell: suspend () -> List<LteCellParam>) = lookupServingStation(local, site, cell,
        match = { matchServingLte(state, it, reference) }, candidates = { servingLteCandidates(state, it) },
        hasPosition = { validStationCoordinates(it.latitude, it.longitude) },
        isWithinRange = { servingStationInRange(it.latitude, it.longitude, reference) })

    @Test fun wrongProvinceInCacheAndSiteDoesNotBlockTheLocalCellEndpoint() = runBlocking {
        val calls = mutableListOf<String>()
        val result = lookup(local = { calls += "local"; listOf(distant) },
            site = { calls += "site"; listOf(distant) }, cell = { calls += "cell"; listOf(nearby) })
        assertEquals(listOf("local", "site", "cell"), calls)
        assertEquals(nearby, result.anchor)
        assertFalse(result.rejectedByDistance)
    }

    @Test fun allDistantResultsHaveAnExplicitRejectionEvenIfTheRemoteServerIsOffline() = runBlocking {
        val result = lookup(local = { listOf(distant) }, site = { throw IOException("offline") }, cell = { emptyList() })
        assertNull(result.anchor)
        assertTrue(result.rejectedByDistance)
        assertFalse(result.matchedWithoutPosition)
        val allOffline = lookup(local = { listOf(distant) }, site = { throw IOException("offline") },
            cell = { throw IOException("offline") })
        assertTrue(allOffline.rejectedByDistance)
    }

    @Test fun replayRejectsBadSavedLinksAgainstTheirOwnRecordingPositionWithoutDeletingData() {
        fun saved(row: LteCellParam, userLat: Double, userLng: Double, key: String) = DriveStationEntity(
            "session", 0, key, 1000, userLat, userLng, DriveStationJson.encode(DriveStationSnapshot(
                rat = "LTE", latitude = row.latitude!!, longitude = row.longitude!!, name = key,
                sectors = listOf(DriveStationSector.from(row)))))
        val origin = Gcj02.wgs84ToGcj02(reference.latitude, reference.longitude)
        val valid = saved(nearby, origin.first, origin.second, "local")
        val bad = saved(distant, origin.first, origin.second, "wrong-province")
        val farOrigin = Gcj02.wgs84ToGcj02(distant.latitude!!, distant.longitude!!)
        val validOtherCity = saved(distant, farOrigin.first, farOrigin.second, "later-trip")
        val originals = listOf(valid, bad, validOtherCity)
        assertEquals(listOf(valid, validOtherCity), driveRecordedStations(originals, 0).map { it.first })
        assertNotNull(DriveStationJson.decode(bad.snapshotJson))
        assertEquals(3, originals.size)
    }
}
