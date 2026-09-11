package com.nvmex.networkhelper.db.entity


import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "lte_cell_params",
    indices = [
        Index(value = ["eci"], unique = true),
        Index(value = ["cellName"])
    ]
)
data class LteCellParamEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val tac: Int? = null,
    val pci: Int? = null,
    val enodebId: Int? = null,
    val earfcn: Int? = null,
    val cellId: Int? = null,
    val eci: Long? = null,
    val localCellId: Int? = null,
    val cellName: String? = null,
    val sectorId: Int? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val azimuth: Int? = null,
    val siteType: String? = null,
    val antennaHeight: Double? = null,
    val source: String? = null,
    val createdAt: String? = null,
    val importedAt: Long = System.currentTimeMillis()
)