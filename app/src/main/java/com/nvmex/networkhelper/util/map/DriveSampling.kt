package com.nvmex.networkhelper.util.map

import com.amap.api.maps.model.LatLng
import com.nvmex.networkhelper.model.map.DrivePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** GPS callbacks never schedule this loop; even a failed tick must not terminate recording. */
internal suspend fun runDriveSamplingLoop(
    active: () -> Boolean, interval: () -> Long, sample: () -> Unit,
    onFailure: (Exception) -> Unit, pause: suspend (Long) -> Unit = { delay(it) }
) {
    while (active()) {
        try { sample() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onFailure(error) }
        pause(interval())
    }
}

/** Radio-only ticks retain both snapshots and break the geometry, never invent a coordinate. */
internal class DriveSamplingBatch {
    private val lastKeys = mutableMapOf<Int, String>()
    private var segmentStart = true

    fun startSegment() { segmentStart = true }
    fun clear() { lastKeys.clear(); startSegment() }

    fun sample(ts: Long, snapshots: Map<Int, NetworkPanelUiState>, position: DriveSamplePosition): List<DrivePoint> {
        val located = position.located
        if (!located) segmentStart = true
        val coordinate = if (located) LatLng(position.lat!!, position.lng!!) else null
        val points = snapshots.toSortedMap().map { (slot, s) ->
            val key = DriveStyle.cellKey(s).takeUnless { it.isBlank() || it == "-" }.orEmpty()
            val handover = key.isNotEmpty() && lastKeys[slot]?.let { it != key } == true
            if (key.isNotEmpty()) lastKeys[slot] = key
            val signal = if (s.cellType.equals("NR", true) && s.ssRsrp != "-" && s.ssRsrp.isNotBlank()) s.ssRsrp else s.rsrp
            DrivePoint(coordinate, ts, slot, DriveStyle.parseDbm(signal), handover, key, s,
                estimated = located && position.source == "INERTIAL", accuracyMeters = position.accuracy.takeIf { located },
                segmentStart = segmentStart,
                rawLat = position.rawLat.takeIf { located }, rawLng = position.rawLng.takeIf { located },
                rawCoordType = position.rawCoordType.takeIf { located }, mapMatchJson = position.mapMatchJson.takeIf { located },
                positionSource = if (located) position.source else "NONE")
        }
        if (points.isNotEmpty() && located) segmentStart = false
        return points
    }
}
