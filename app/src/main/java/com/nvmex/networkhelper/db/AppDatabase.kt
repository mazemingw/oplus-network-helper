package com.nvmex.networkhelper.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nvmex.networkhelper.db.dao.LteCellParamDao
import com.nvmex.networkhelper.db.dao.NrCellParamDao
import com.nvmex.networkhelper.db.entity.LteCellParamEntity
import com.nvmex.networkhelper.db.entity.NrCellParamEntity
import com.nvmex.networkhelper.db.entity.ManagedCell
import com.nvmex.networkhelper.db.entity.ManagedCellDao
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NrCellParamEntity::class,
        LteCellParamEntity::class,
        ManagedCell::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nrCellParamDao(): NrCellParamDao
    abstract fun lteCellParamDao(): LteCellParamDao
    abstract fun managedCellDao(): ManagedCellDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lte_cell_params ADD COLUMN extraInfo TEXT")
                db.execSQL("ALTER TABLE lte_cell_params ADD COLUMN stationType TEXT")
                db.execSQL("ALTER TABLE managed_cells ADD COLUMN extraInfo TEXT")
                db.execSQL("ALTER TABLE managed_cells ADD COLUMN stationType TEXT")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS managed_cells (rat TEXT NOT NULL, ci TEXT NOT NULL, name TEXT NOT NULL, latitude REAL, longitude REAL, azimuth INTEGER, tac INTEGER, arfcn INTEGER, pci INTEGER, enodebId INTEGER, cellId INTEGER, sectorId INTEGER, siteType TEXT, antennaHeight REAL, source TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY(rat, ci))")
                val imported = "(source IS NULL OR TRIM(source) = '' OR LOWER(source) LIKE '%import%')"
                db.execSQL("INSERT OR IGNORE INTO managed_cells SELECT 'LTE', CAST(eci AS TEXT), COALESCE(cellName,''), latitude, longitude, azimuth, tac, earfcn, pci, enodebId, cellId, sectorId, siteType, antennaHeight, source, importedAt FROM lte_cell_params WHERE eci IS NOT NULL AND $imported")
                db.execSQL("INSERT OR IGNORE INTO managed_cells SELECT 'NR', gcellId, COALESCE(cellName,''), latitude, longitude, azimuth, nrTac, nrArfcn, nrPci, NULL, NULL, NULL, siteType, antennaHeight, source, importedAt FROM nr_cell_params WHERE $imported")
                db.execSQL("DELETE FROM lte_cell_params WHERE eci IS NOT NULL AND $imported")
                db.execSQL("DELETE FROM nr_cell_params WHERE $imported")
            }
        }
    }
}
