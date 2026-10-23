package com.nvmex.networkhelper.db.entity

import androidx.room.*

/** User-maintained records are physically separate from cloud query caches. */
@Entity(tableName = "managed_cells", primaryKeys = ["rat", "ci"])
data class ManagedCell(
    val rat: String, val ci: String, val name: String = "",
    val latitude: Double? = null, val longitude: Double? = null, val azimuth: Int? = null,
    val tac: Int? = null, val arfcn: Int? = null, val pci: Int? = null,
    val enodebId: Int? = null, val cellId: Int? = null, val sectorId: Int? = null,
    val siteType: String? = null, val antennaHeight: Double? = null,
    val source: String? = null, val updatedAt: Long = System.currentTimeMillis()
) {
    fun validate(requireName: Boolean = true) {
        require(rat == "LTE" || rat == "NR") { "仅支持 LTE / NR" }
        require(ci.toLongOrNull()?.let { it > 0 && it <= if (rat == "LTE") 268435455L else 68719476735L } == true) { "小区长号无效" }
        require(!requireName || name.isNotBlank()) { "请输入基站名称" }
        require(latitude?.let { it.isFinite() && it in -90.0..90.0 } != false &&
            longitude?.let { it.isFinite() && it in -180.0..180.0 } != false &&
            (latitude == null) == (longitude == null)) { "坐标无效或不完整" }
        require(azimuth == null || azimuth in 0..360) { "方位角范围为 0–360°" }
        require(tac == null || tac >= 0) { "TAC 无效" }
        require(arfcn == null || arfcn >= 0) { "频点无效" }
        require(pci == null || pci in 0..if (rat == "LTE") 503 else 1007) { "PCI 无效" }
        require(antennaHeight == null || antennaHeight.isFinite() && antennaHeight >= 0) { "天线挂高无效" }
        require(sectorId == null || sectorId >= 0) { "扇区号无效" }
    }
    fun lte() = LteCellParamEntity(eci = ci.toLong(), cellName = name, latitude = latitude, longitude = longitude,
        azimuth = azimuth, tac = tac, earfcn = arfcn, pci = pci, enodebId = enodebId ?: (ci.toLong() shr 8).toInt(),
        cellId = cellId ?: (ci.toLong() and 255).toInt(), sectorId = sectorId, siteType = siteType,
        antennaHeight = antennaHeight, source = "本地用户数据")
    fun nr() = NrCellParamEntity(gcellId = ci, cellName = name, latitude = latitude, longitude = longitude,
        azimuth = azimuth, nrTac = tac, nrArfcn = arfcn, nrPci = pci, siteType = siteType,
        antennaHeight = antennaHeight, source = "本地用户数据")
    companion object {
        fun from(row: LteCellParamEntity) = ManagedCell("LTE", row.eci.toString(), row.cellName.orEmpty(), row.latitude,
            row.longitude, row.azimuth, row.tac, row.earfcn, row.pci, row.enodebId, row.cellId, row.sectorId,
            row.siteType, row.antennaHeight, row.source, row.importedAt)
        fun from(row: NrCellParamEntity) = ManagedCell("NR", row.gcellId, row.cellName.orEmpty(), row.latitude,
            row.longitude, row.azimuth, row.nrTac, row.nrArfcn, row.nrPci, siteType = row.siteType,
            antennaHeight = row.antennaHeight, source = row.source, updatedAt = row.importedAt)
    }
}

@Dao
interface ManagedCellDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<ManagedCell>)
    @Query("SELECT * FROM managed_cells WHERE rat=:rat AND ci=:ci LIMIT 1")
    suspend fun find(rat: String, ci: String): ManagedCell?
    @Query("SELECT * FROM managed_cells WHERE rat=:rat AND tac=:tac AND arfcn=:arfcn AND pci=:pci LIMIT 1")
    suspend fun match(rat: String, tac: Int?, arfcn: Int?, pci: Int?): ManagedCell?
    @Query("SELECT * FROM managed_cells WHERE name LIKE '%' || :search || '%' OR ci LIKE '%' || :search || '%' ORDER BY updatedAt DESC, rat, ci LIMIT :limit OFFSET :offset")
    suspend fun list(search: String, limit: Int, offset: Int): List<ManagedCell>
    @Query("SELECT COUNT(*) FROM managed_cells")
    suspend fun count(): Int
    @Query("DELETE FROM managed_cells WHERE rat=:rat AND ci=:ci")
    suspend fun delete(rat: String, ci: String)
}
