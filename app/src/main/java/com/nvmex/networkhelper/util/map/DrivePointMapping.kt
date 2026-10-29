package com.nvmex.networkhelper.util.map

import com.amap.api.maps.model.LatLng
import com.nvmex.networkhelper.model.map.DrivePoint

internal fun DrivePoint.toDriveEntity(sessionId: String) = DrivePointEntity(
    sessionId = sessionId, ts = ts, simSlot = simSlot,
    lat = latLng?.latitude, lng = latLng?.longitude, rsrpDbm = rsrpDbm,
    isHandover = isHandover, cellKey = cellKey,
    snapshotJson = SnapshotJson.withMapMatch(SnapshotJson.toJson(snapshot), mapMatchJson),
    estimated = estimated, accuracyMeters = accuracyMeters, segmentStart = segmentStart,
    rawLat = rawLat, rawLng = rawLng, rawCoordType = rawCoordType, positionSource = positionSource
)

internal fun DrivePointEntity.toDrivePointOrNull(): DrivePoint? {
    val snap = runCatching { SnapshotJson.fromJson(snapshotJson) }
        .getOrElse { return null }
    return DrivePoint(
        latLng = if (lat != null && lng != null && driveValidPosition(lat, lng, 0f)) LatLng(lat, lng) else null,
        ts = ts,
        simSlot = simSlot,
        rsrpDbm = rsrpDbm,
        isHandover = isHandover,
        cellKey = cellKey,
        snapshot = snap,
        estimated = estimated,
        accuracyMeters = accuracyMeters,
        segmentStart = segmentStart,
        rawLat = rawLat, rawLng = rawLng, rawCoordType = rawCoordType,
        mapMatchJson = SnapshotJson.mapMatch(snapshotJson), positionSource = positionSource
    )
}
