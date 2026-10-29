package com.nvmex.networkhelper.util.map

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android SQLite and generated Room code, without location permission or modem access. */
@RunWith(AndroidJUnit4::class)
class DriveRoomRecordingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun point(ts: Long, slot: Int, lat: Double? = 31.0) = DrivePointEntity(
        sessionId = "tunnel", ts = ts, simSlot = slot, lat = lat, lng = lat?.let { 121.0 },
        rsrpDbm = -80 - slot, isHandover = false, cellKey = "cell-$slot",
        snapshotJson = SnapshotJson.toJson(NetworkPanelUiState(subId = slot + 1, cellType = "NR", band = "N41", pci = "20")),
        estimated = lat != null, positionSource = if (lat == null) "NONE" else "INERTIAL"
    )

    @Test fun radioOnlyRowsAndDualSimCoordinatesPersistAndReplayWithoutFalseDistance() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).build()
        try {
            val dao = db.drivePointDao()
            dao.recordBatch(listOf(point(1000, 0), point(1000, 1)), "测试城市")
            dao.recordBatch(listOf(point(4000, 0, null), point(4000, 1, null)), "")
            dao.recordBatch(listOf(point(7000, 0, 31.0001), point(7000, 1, 31.0001)), "")
            assertEquals(0.0, dao.getSession("tunnel")!!.distanceMeters, 0.0)
            dao.recordBatch(listOf(point(10000, 0, 31.0002), point(10000, 1, 31.0002)), "")
            val rows = dao.getBySession("tunnel")
            assertEquals(8, rows.size)
            assertEquals(2, rows.count { it.lat == null && it.lng == null && it.positionSource == "NONE" })
            assertEquals(11.12, dao.getSession("tunnel")!!.distanceMeters, .1)
            assertEquals("测试城市", dao.getSession("tunnel")!!.cityName)
            assertEquals(8, dao.getSessionSummaries(10).single().pointCount)
            assertEquals(1000L, dao.getFirstPoint("tunnel")!!.ts)
            assertEquals(8, DriveHistory(rows.mapNotNull { it.toDrivePointOrNull() }).pointsBySlot.values.sumOf { it.size })
            val csv = DriveCsv.decode(DriveCsv.encode(rows, emptyList(), "测试城市")).single()
            assertEquals(rows.map { it.lat }, csv.points.map { it.lat })
            assertEquals(rows.map { it.positionSource }, csv.points.map { it.positionSource })
        } finally { db.close() }
    }

    @Test fun wholeDualSimTickRollsBackIfOneRowCannotBeWritten() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java).build()
        try {
            val dao = db.drivePointDao()
            dao.recordBatch(listOf(point(1000, 0).copy(id = 1)), "")
            val result = runCatching { dao.recordBatch(listOf(point(4000, 0).copy(id = 2), point(4000, 1).copy(id = 1)), "") }
            assertTrue(result.isFailure)
            assertEquals(1, dao.getBySession("tunnel").size)
            assertEquals(0.0, dao.getSession("tunnel")!!.distanceMeters, 0.0)
        } finally { db.close() }
    }

    @Test fun version6MigrationPreservesIdsSnapshotsCoordinatesAndOtherTables() = runBlocking {
        val name = "tunnel-migration-${System.nanoTime()}.db"
        try {
            val original = Room.databaseBuilder(context, AppDb::class.java, name).build()
            val old = point(1000, 1).copy(id = 77, rawLat = 30.9, rawLng = 120.9, rawCoordType = "WGS84_ESTIMATED")
            original.drivePointDao().recordBatch(listOf(old), "旧会话城市")
            original.close()
            // Reconstruct the shipped v6 drive table. Keep the actual Room-created auxiliary tables.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
                sql.execSQL("""CREATE TABLE drive_points_v6 (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId TEXT NOT NULL, ts INTEGER NOT NULL,
                    simSlot INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, rsrpDbm INTEGER,
                    isHandover INTEGER NOT NULL, cellKey TEXT NOT NULL, snapshotJson TEXT NOT NULL,
                    estimated INTEGER NOT NULL DEFAULT 0, accuracyMeters REAL, segmentStart INTEGER NOT NULL DEFAULT 0,
                    rawLat REAL, rawLng REAL, rawCoordType TEXT)""")
                sql.execSQL("""INSERT INTO drive_points_v6 SELECT id, sessionId, ts, simSlot, lat, lng, rsrpDbm,
                    isHandover, cellKey, snapshotJson, estimated, accuracyMeters, segmentStart, rawLat, rawLng,
                    rawCoordType FROM drive_points""")
                sql.execSQL("DROP TABLE drive_points")
                sql.execSQL("ALTER TABLE drive_points_v6 RENAME TO drive_points")
                sql.execSQL("CREATE INDEX index_drive_points_sessionId_ts ON drive_points(sessionId, ts)")
                sql.version = 6
            }
            val migrated = Room.databaseBuilder(context, AppDb::class.java, name).addMigrations(AppDb.MIGRATION_6_7).build()
            try {
                val dao = migrated.drivePointDao()
                assertEquals(old, dao.getBySession("tunnel").single())
                assertEquals("旧会话城市", dao.getSession("tunnel")!!.cityName)
                dao.recordBatch(listOf(point(4000, 0, null), point(4000, 1, null)), "")
                assertEquals(3, dao.getBySession("tunnel").size)
                assertEquals(77L, dao.getFirstPoint("tunnel")!!.id)
                assertTrue(dao.lastPoint("tunnel")!!.id > 77)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
