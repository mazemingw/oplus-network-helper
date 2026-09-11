package com.nvmex.networkhelper.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "nr_cell_params",
    indices = [
        Index(value = ["gcellId"], unique = true),
        Index(value = ["cellName"])
    ]
)
data class NrCellParamEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val gcellId: String,
    val cellName: String? = null,
    val longitude: Double? = null,
    val latitude: Double? = null,
    val azimuth: Int? = null,
    val nrPci: Int? = null,
    val nrArfcn: Int? = null,
    val nrTac: Int? = null,
    val siteType: String? = null,
    val antennaHeight: Double? = null,
    val source: String? = null,
    val importedAt: Long = System.currentTimeMillis()
)