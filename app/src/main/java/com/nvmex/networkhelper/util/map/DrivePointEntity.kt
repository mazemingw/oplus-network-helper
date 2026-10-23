package com.nvmex.networkhelper.util.map

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(tableName = "drive_points", indices = [Index(value = ["sessionId", "ts"])])
data class DrivePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,     // ✅ 新增：一次采样会话
    val ts: Long,
    val simSlot: Int = 0,
    val lat: Double,
    val lng: Double,
    val rsrpDbm: Int?,
    val isHandover: Boolean,
    val cellKey: String,
    val snapshotJson: String,
    @androidx.room.ColumnInfo(defaultValue = "0") val estimated: Boolean = false,
    val accuracyMeters: Float? = null,
    @androidx.room.ColumnInfo(defaultValue = "0") val segmentStart: Boolean = false,
    val rawLat: Double? = null,
    val rawLng: Double? = null,
    val rawCoordType: String? = null
)
