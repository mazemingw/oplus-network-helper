package com.nvmex.networkhelper.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nvmex.networkhelper.db.entity.NrCellParamEntity

@Dao
interface NrCellParamDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<NrCellParamEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: NrCellParamEntity): Long

    @Update
    suspend fun update(items: List<NrCellParamEntity>)

    @Update
    suspend fun update(entity: NrCellParamEntity)

    @Query("SELECT * FROM nr_cell_params WHERE gcellId = :gcellId LIMIT 1")
    suspend fun findByGcellId(gcellId: String): NrCellParamEntity?

    @Query("DELETE FROM nr_cell_params WHERE gcellId = :gcellId")
    suspend fun deleteByGcellId(gcellId: String): Int

    @Query("""
        SELECT * FROM nr_cell_params
        WHERE (:nrTac IS NULL OR nrTac = :nrTac)
          AND (:nrArfcn IS NULL OR nrArfcn = :nrArfcn)
          AND (:nrPci IS NULL OR nrPci = :nrPci)
        LIMIT 1
    """)
    suspend fun findFirstByTacArfcnPci(
        nrTac: Int?,
        nrArfcn: Int?,
        nrPci: Int?
    ): NrCellParamEntity?

    @Query("DELETE FROM nr_cell_params")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM nr_cell_params")
    suspend fun count(): Int
}
