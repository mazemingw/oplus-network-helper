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
        CellOverlayCacheEntity::class, // ✅新增
    ],
    version = 3,
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

        fun get(context: Context): AppDb =
            INS ?: synchronized(this) {
                INS ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "networkhelper.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INS = it }
            }
    }
}
