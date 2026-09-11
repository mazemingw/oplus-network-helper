package com.nvmex.networkhelper.util.map

//基站标记在用
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cell_overlay_cache",
    indices = [
        Index(value = ["baseKey"], name = "idx_cell_overlay_cache_baseKey"),
        Index(value = ["ts"], name = "idx_cell_overlay_cache_ts"),
    ]
)
data class CellOverlayCacheEntity(
    @PrimaryKey val key: String, // baseKey#idx
    val baseKey: String,         // ✅用于一次查出多条
    val tac: Int,
    val earfcn: Int,
    val eci: Long?,
    val pci: Int?,
    val lat: Double,
    val lng: Double,

    // 可选：画三角时可能有用
    val azimuth: Int? = null,
    val enodebId: Int? = null,
    val cellId: Int? = null,
    val cellName: String? = null,

    val source: String,
    val ts: Long,
)

//fun buildOverlayBaseKey(tac: Int, earfcn: Int, eci: Long?, pci: Int?): String {
//    val e = eci?.toString() ?: "-"
//    val p = pci?.toString() ?: "-"
//    return "$tac-$earfcn-$e-$p"
//}