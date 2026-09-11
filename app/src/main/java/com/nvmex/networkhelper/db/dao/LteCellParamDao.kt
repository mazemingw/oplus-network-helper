package com.nvmex.networkhelper.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nvmex.networkhelper.db.entity.LteCellParamEntity

@Dao
interface LteCellParamDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<LteCellParamEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LteCellParamEntity): Long

    @Update
    suspend fun update(items: List<LteCellParamEntity>)

    @Update
    suspend fun update(entity: LteCellParamEntity)

    @Query("SELECT * FROM lte_cell_params WHERE eci = :eci LIMIT 1")
    suspend fun findByEci(eci: Long): LteCellParamEntity?

    @Query("DELETE FROM lte_cell_params WHERE eci = :eci")
    suspend fun deleteByEci(eci: Long): Int

    @Query("""
        SELECT * FROM lte_cell_params
        WHERE (:tac IS NULL OR tac = :tac)
          AND (:earfcn IS NULL OR earfcn = :earfcn)
          AND (:pci IS NULL OR pci = :pci)
          AND (:cellId IS NULL OR cellId = :cellId)
        LIMIT 1
    """)
    suspend fun findFirstByParams(
        tac: Int?,
        earfcn: Int?,
        pci: Int?,
        cellId: Int?
    ): LteCellParamEntity?

    @Query("DELETE FROM lte_cell_params")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM lte_cell_params")
    suspend fun count(): Int
}
