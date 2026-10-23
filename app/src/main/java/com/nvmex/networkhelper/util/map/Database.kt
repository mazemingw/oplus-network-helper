package com.nvmex.networkhelper.util.map

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DrivePointEntity::class,
        DriveSessionEntity::class,
        DriveStationEntity::class,
        CellOverlayCacheEntity::class, // ✅新增
    ],
    version = 6,
    exportSchema = false  // 你原来就是 false，保持不变
)
abstract class AppDb : RoomDatabase() {

    abstract fun drivePointDao(): DrivePointDao

    // ✅新增：给 overlay 缓存用
    abstract fun cellOverlayCacheDao(): CellOverlayCacheDao

    companion object {
        @Volatile private var INS: AppDb? = null

        // ✅ migration: 1 -> 2 只做一件事：创建新表 + 索引
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
            CREATE TABLE IF NOT EXISTS cell_overlay_cache (
                `key` TEXT NOT NULL PRIMARY KEY,
                baseKey TEXT NOT NULL,
                tac INTEGER NOT NULL,
                earfcn INTEGER NOT NULL,
                eci INTEGER,
                pci INTEGER,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                azimuth INTEGER,
                enodebId INTEGER,
                cellId INTEGER,
                cellName TEXT,
                source TEXT NOT NULL,
                ts INTEGER NOT NULL
            )
            """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cell_overlay_cache_baseKey ON cell_overlay_cache(baseKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cell_overlay_cache_ts ON cell_overlay_cache(ts)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drive_points ADD COLUMN simSlot INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drive_points ADD COLUMN estimated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE drive_points ADD COLUMN accuracyMeters REAL")
                db.execSQL("ALTER TABLE drive_points ADD COLUMN segmentStart INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_drive_points_sessionId_ts ON drive_points (sessionId, ts)")
                db.execSQL("CREATE TABLE IF NOT EXISTS drive_sessions (sessionId TEXT NOT NULL PRIMARY KEY, cityName TEXT NOT NULL, distanceMeters REAL NOT NULL)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS drive_stations (sessionId TEXT NOT NULL, simSlot INTEGER NOT NULL, cellKey TEXT NOT NULL, ts INTEGER NOT NULL, userLat REAL NOT NULL, userLng REAL NOT NULL, snapshotJson TEXT NOT NULL, PRIMARY KEY(sessionId, simSlot, cellKey))")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drive_points ADD COLUMN rawLat REAL")
                db.execSQL("ALTER TABLE drive_points ADD COLUMN rawLng REAL")
                db.execSQL("ALTER TABLE drive_points ADD COLUMN rawCoordType TEXT")
            }
        }

        fun get(context: Context): AppDb =
            INS ?: synchronized(this) {
                INS ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "networkhelper.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INS = it }
            }
    }
}
