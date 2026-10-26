package com.nvmex.networkhelper.util.map

import androidx.room.Entity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.nvmex.networkhelper.network.map.LteCellParam
import com.nvmex.networkhelper.network.model.NrCellParamItem
import com.nvmex.networkhelper.model.map.DrivePoint

@Entity(tableName = "drive_stations", primaryKeys = ["sessionId", "simSlot", "cellKey"])
data class DriveStationEntity(
    val sessionId: String,
    val simSlot: Int,
    val cellKey: String,
    val ts: Long,
    val userLat: Double,
    val userLng: Double,
    val snapshotJson: String
)

/** Stable on-disk fields: keep the actual matched station and its antenna faces, independent of the server/cache. */
internal data class DriveStationSnapshot(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("rat") val rat: String,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("name") val name: String?,
    @SerializedName("sectors") val sectors: List<DriveStationSector>
)

internal data class DriveStationSector(
    @SerializedName("ci") val ci: String?,
    @SerializedName("tac") val tac: Int?,
    @SerializedName("arfcn") val arfcn: Int?,
    @SerializedName("pci") val pci: Int?,
    @SerializedName("siteId") val siteId: Int?,
    @SerializedName("cellId") val cellId: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("latitude") val latitude: Double?,
    @SerializedName("longitude") val longitude: Double?,
    @SerializedName("azimuth") val azimuth: Int?,
    @SerializedName("siteType") val siteType: String? = null
) {
    fun lte() = LteCellParam(eci = ci?.toLongOrNull(), tac = tac, earfcn = arfcn, pci = pci,
        enodeb_id = siteId, cell_id = cellId, cell_name = name, latitude = latitude, longitude = longitude,
        azimuth = azimuth, site_type = siteType, source = "session")
    fun nr() = NrCellParamItem(gcell_id = ci, nr_tac = tac, nr_arfcn = arfcn, nr_pci = pci,
        cell_name = name, latitude = latitude, longitude = longitude, azimuth = azimuth, site_type = siteType, source = "session")
    companion object {
        fun from(row: LteCellParam) = DriveStationSector(row.eci?.toString(), row.tac, row.earfcn, row.pci,
            row.enodeb_id, row.cell_id, row.cell_name, row.latitude, row.longitude, row.azimuth, row.site_type)
        fun from(row: NrCellParamItem) = DriveStationSector(row.gcell_id, row.nr_tac, row.nr_arfcn, row.nr_pci,
            null, null, row.cell_name, row.latitude, row.longitude, row.azimuth, row.site_type)
    }
}

internal object DriveStationJson {
    private val gson = Gson()
    fun encode(snapshot: DriveStationSnapshot): String = gson.toJson(snapshot)
    fun decode(json: String): DriveStationSnapshot? = runCatching {
        gson.fromJson(json, DriveStationSnapshot::class.java).takeIf {
            it.version == 1 && it.rat in listOf("LTE", "NR") &&
                driveValidPosition(it.latitude, it.longitude, 0f) && it.sectors.isNotEmpty()
        }
    }.getOrNull()
}

internal fun driveRecordedStations(stations: List<DriveStationEntity>, simSlot: Int): List<Pair<DriveStationEntity, DriveStationSnapshot>> =
    stations.filter { it.simSlot == simSlot && driveValidPosition(it.userLat, it.userLng, 0f) }.mapNotNull { row ->
        DriveStationJson.decode(row.snapshotJson)?.takeIf {
            servingStationInRange(it.latitude, it.longitude, servingStationReference(row.userLat, row.userLng))
        }?.let { row to it }
    }

/** Only the station matching the selected historical cell may supply antennas or a connection line. */
internal fun driveHistoryServingStation(stations: List<Pair<DriveStationEntity, DriveStationSnapshot>>, cellKey: String?):
    Pair<DriveStationEntity, DriveStationSnapshot>? = stations.firstOrNull { it.first.cellKey == cellKey }

/** Prefer the identity actually saved with the point; never cross SIMs or substitute a neighbouring station. */
internal fun driveHistoryStationForPoint(stations: List<Pair<DriveStationEntity, DriveStationSnapshot>>, point: DrivePoint?):
    Pair<DriveStationEntity, DriveStationSnapshot>? {
    if (point == null) return null
    for (key in listOf(point.cellKey, DriveStyle.cellKey(point.snapshot)).distinct()) {
        stations.firstOrNull { (record, snapshot) -> record.simSlot == point.simSlot && record.cellKey == key &&
            snapshot.rat.equals(point.snapshot.cellType, true) }?.let { return it }
    }
    return null
}
