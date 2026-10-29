package com.nvmex.networkhelper.model.map

import com.amap.api.maps.model.LatLng
import com.nvmex.networkhelper.model.network.NetworkPanelUiState

data class DrivePoint(
    val latLng: LatLng?,
    val ts: Long,
    val simSlot: Int = 0,
    val rsrpDbm: Int?,              // 可能取不到
    val isHandover: Boolean,        // 是否小区切换点（方块）
    val cellKey: String,            // 用于判定切换
    val snapshot: NetworkPanelUiState,
    val estimated: Boolean = false,
    val accuracyMeters: Float? = null,
    val segmentStart: Boolean = false,
    val rawLat: Double? = null,
    val rawLng: Double? = null,
    val rawCoordType: String? = null,
    val mapMatchJson: String? = null,
    val positionSource: String = if (estimated) "INERTIAL" else if (latLng == null) "NONE" else "SDK"
)
