package com.nvmex.networkhelper.util.map

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Entity(tableName = "drive_sessions")
data class DriveSessionEntity(
    @PrimaryKey val sessionId: String,
    val cityName: String = "",
    val distanceMeters: Double = 0.0
)

/** Page destruction must not cancel a sampling tick that has already been queued for Room. */
internal object DrivePersistence {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
