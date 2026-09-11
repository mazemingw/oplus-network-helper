package com.nvmex.networkhelper.util.map

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

//基站标记点在用
@Dao
interface CellOverlayCacheDao {

    @Query("SELECT * FROM cell_overlay_cache WHERE baseKey = :baseKey ORDER BY key ASC")
    suspend fun listByBaseKey(baseKey: String): List<CellOverlayCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(list: List<CellOverlayCacheEntity>)

    @Query("DELETE FROM cell_overlay_cache WHERE baseKey = :baseKey")
    suspend fun deleteByBaseKey(baseKey: String)

    @Query("DELETE FROM cell_overlay_cache WHERE ts < :minTs")
    suspend fun deleteOlderThan(minTs: Long)
}