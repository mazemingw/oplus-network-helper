package com.nvmex.networkhelper.util.map

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "drive_points")
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
    val snapshotJson: String
)
