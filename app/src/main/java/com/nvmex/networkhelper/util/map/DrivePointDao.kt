package com.nvmex.networkhelper.util.map

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.nvmex.networkhelper.util.map.matching.FalconPointUpdate

@Dao
interface DrivePointDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: DrivePointEntity): Long

    // Room inserts the entire sampling tick in one transaction, including both SIMs.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBatch(points: List<DrivePointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(session: DriveSessionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStations(stations: List<DriveStationEntity>)

    @Query("SELECT * FROM drive_stations WHERE sessionId = :sessionId ORDER BY ts ASC")
    suspend fun getStations(sessionId: String): List<DriveStationEntity>

    @Query("DELETE FROM drive_stations WHERE sessionId = :sessionId")
    suspend fun deleteStations(sessionId: String)

    @Query("SELECT * FROM drive_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): DriveSessionEntity?

    @Query("SELECT * FROM drive_points WHERE sessionId = :sessionId AND lat IS NOT NULL AND lng IS NOT NULL ORDER BY ts ASC, id ASC LIMIT 1")
    suspend fun getFirstPoint(sessionId: String): DrivePointEntity?

    @Query("UPDATE drive_sessions SET cityName = :city WHERE sessionId = :sessionId AND cityName = ''")
    suspend fun setCityIfEmpty(sessionId: String, city: String)

    @Query("SELECT * FROM drive_points WHERE sessionId = :sessionId ORDER BY ts DESC, id DESC LIMIT 1")
    suspend fun lastPoint(sessionId: String): DrivePointEntity?

    @Transaction
    suspend fun recordBatch(points: List<DrivePointEntity>, cityName: String) {
        if (points.isEmpty()) return
        val sid = points.first().sessionId
        val meta = getSession(sid)
        val previous = lastPoint(sid)
        val track = (listOfNotNull(previous) + points).map { DriveTrackFix(it.ts, it.lat, it.lng, it.segmentStart) }
        insertBatch(points)
        putSession(DriveSessionEntity(sid, meta?.cityName?.takeIf { it.isNotBlank() } ?: cityName,
            (meta?.distanceMeters ?: 0.0) + driveTrackLength(track)))
    }

    @Query("DELETE FROM drive_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionMetadata(sessionId: String)

    @Transaction
    suspend fun deleteRecording(sessionId: String) {
        deleteStations(sessionId)
        deleteSession(sessionId)
        deleteSessionMetadata(sessionId)
    }

    @Query("DELETE FROM drive_points WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("SELECT * FROM drive_points WHERE sessionId = :sessionId ORDER BY ts ASC, id ASC")
    suspend fun getBySession(sessionId: String): List<DrivePointEntity>

    @Query("SELECT * FROM drive_points WHERE sessionId = :sessionId AND ts BETWEEN :start AND :end")
    suspend fun getPointRange(sessionId: String, start: Long, end: Long): List<DrivePointEntity>

    @Query("UPDATE drive_points SET snapshotJson = :snapshot WHERE id = :id")
    suspend fun updatePointSnapshot(id: Long, snapshot: String)

    @Transaction
    suspend fun applyFalconCorrections(sessionId: String, updates: List<FalconPointUpdate>) {
        if (updates.isEmpty()) return
        val changes = updates.associateBy { it.simSlot to it.timestamp }
        getPointRange(sessionId, updates.minOf { it.timestamp }, updates.maxOf { it.timestamp }).forEach { point ->
            changes[point.simSlot to point.ts]?.let { update ->
                updatePointSnapshot(point.id, SnapshotJson.withMapMatch(point.snapshotJson, update.metadata))
            }
        }
    }

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

