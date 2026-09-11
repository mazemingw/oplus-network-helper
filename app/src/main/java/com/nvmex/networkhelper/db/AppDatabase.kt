package com.nvmex.networkhelper.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nvmex.networkhelper.db.dao.LteCellParamDao
import com.nvmex.networkhelper.db.dao.NrCellParamDao
import com.nvmex.networkhelper.db.entity.LteCellParamEntity
import com.nvmex.networkhelper.db.entity.NrCellParamEntity

@Database(
    entities = [
        NrCellParamEntity::class,
        LteCellParamEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nrCellParamDao(): NrCellParamDao
    abstract fun lteCellParamDao(): LteCellParamDao
}