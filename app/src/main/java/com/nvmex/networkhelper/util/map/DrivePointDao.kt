package com.nvmex.networkhelper.util.map

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DrivePointDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: DrivePointEntity): Long

    @Query("DELETE FROM drive_points WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM drive_points WHERE sessionId = :sessionId ORDER BY ts ASC")
    suspend fun getBySession(sessionId: String): List<DrivePointEntity>

    @Query("SELECT * FROM drive_points ORDER BY ts DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<DrivePointEntity>

    @Query(
        """
        SELECT sessionId, MIN(ts) AS startTs, MAX(ts) AS endTs, COUNT(*) AS pointCount
        FROM drive_points
        GROUP BY sessionId
        ORDER BY endTs DESC
        LIMIT :limit
        """
    )
    suspend fun getSessionSummaries(limit: Int): List<DriveSessionSummary>
}

data class DriveSessionSummary(
    val sessionId: String,
    val startTs: Long,
    val endTs: Long,
    val pointCount: Int
)

