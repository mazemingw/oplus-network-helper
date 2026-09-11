package com.nvmex.networkhelper.repository

import com.nvmex.networkhelper.db.dao.LteCellParamDao
import com.nvmex.networkhelper.db.dao.NrCellParamDao
import com.nvmex.networkhelper.db.entity.LteCellParamEntity
import com.nvmex.networkhelper.db.entity.NrCellParamEntity
import com.nvmex.networkhelper.model.cellparam.LteCellParam as ImportLteCellParam
import com.nvmex.networkhelper.model.cellparam.NrCellParam
import com.nvmex.networkhelper.model.menu.LteCellParamRow
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.menu.LteCellQueryResp
import com.nvmex.networkhelper.network.map.LteSiteQueryResp
import com.nvmex.networkhelper.network.model.NrCellParamItem
import com.nvmex.networkhelper.network.model.NrCellQueryResp
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.network.model.NrSiteQueryResp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CellParamLocalRepository @Inject constructor(
    private val nrDao: NrCellParamDao,
    private val lteDao: LteCellParamDao
) {

    /**
     * 导入 NR 文件后写入本地库
     * 唯一键：gcellId
     */
    suspend fun saveNr(items: List<NrCellParam>): LocalSaveResult {
        if (items.isEmpty()) {
            return LocalSaveResult(false, 0, 0, "NR 数据为空")
        }

        var inserted = 0
        var updated = 0

        for (item in items) {
            val gcellId = item.gcellId.trim()
            if (gcellId.isBlank()) continue

            val entity = item.toEntity()
            val rowId = nrDao.insertIgnore(listOf(entity)).firstOrNull() ?: -1L
            if (rowId > 0) {
                inserted++
            } else {
                val old = nrDao.findByGcellId(gcellId)
                if (old != null) {
                    nrDao.update(listOf(entity.copy(id = old.id)))
                    updated++
                }
            }
        }

        return LocalSaveResult(
            success = true,
            inserted = inserted,
            updated = updated,
            message = "本地写入完成：新增 $inserted 条，更新 $updated 条"
        )
    }

    /**
     * 导入 LTE 文件后写入本地库
     * 唯一键：eci
     */
    suspend fun saveLte(items: List<ImportLteCellParam>): LocalSaveResult {
        if (items.isEmpty()) {
            return LocalSaveResult(false, 0, 0, "LTE 数据为空")
        }

        var inserted = 0
        var updated = 0

        for (item in items) {
            val entity = item.toEntity()
            val eci = entity.eci ?: continue

            val rowId = lteDao.insertIgnore(listOf(entity)).firstOrNull() ?: -1L
            if (rowId > 0) {
                inserted++
            } else {
                val old = lteDao.findByEci(eci)
                if (old != null) {
                    lteDao.update(listOf(entity.copy(id = old.id)))
                    updated++
                }
            }
        }

        return LocalSaveResult(
            success = true,
            inserted = inserted,
            updated = updated,
            message = "本地写入完成：新增 $inserted 条，更新 $updated 条"
        )
    }

    /**
     * NR 查询：优先本地
     * 优先 gcellId，其次 nrTac + nrArfcn + nrPci
     */
    suspend fun queryNr(body: NrQueryReq): NrCellQueryResp? {
        val gcellId = body.gcellId?.trim().orEmpty()

        val entity = if (gcellId.isNotBlank()) {
            nrDao.findByGcellId(gcellId)
        } else {
            nrDao.findFirstByTacArfcnPci(
                nrTac = body.nrTac,
                nrArfcn = body.nrArfcn,
                nrPci = body.nrPci
            )
        }

        return entity?.toNrQueryResp()
    }

    /**
     * LTE 查询：优先本地
     * 优先 eci，其次 tac + earfcn + pci + cellId
     */
    suspend fun queryLte(body: LteCellQueryBody): LteCellQueryResp? {
        val entity = if (body.eci != null) {
            lteDao.findByEci(body.eci)
        } else {
            lteDao.findFirstByParams(
                tac = body.tac,
                earfcn = body.earfcn,
                pci = body.pci,
                cellId = body.cell_id
            )
        }

        return entity?.toLteQueryResp()
    }

    suspend fun queryNrSite(body: NrQueryReq): NrSiteQueryResp? {
        val local = queryNr(body) ?: return null
        return NrSiteQueryResp(
            match_level = local.match_level ?: "local",
            site_match = "local",
            count = local.count,
            total_count = local.total_count,
            truncated = local.truncated,
            data = local.data
        )
    }

    suspend fun queryLteSite(body: LteCellQueryBody): LteSiteQueryResp? {
        val local = queryLte(body) ?: return null
        return local.toLteSiteQueryResp()
    }

    /**
     * NR 网络查询成功后回填本地
     * 这里只取 data 的第一条
     */
    suspend fun saveNrQueryResp(resp: NrCellQueryResp) {
        val first = resp.data.firstOrNull() ?: return

        val gcellId = first.gcell_id?.trim().orEmpty()
        if (gcellId.isBlank()) return

        val entity = first.toNrEntity(gcellId)
        val old = nrDao.findByGcellId(gcellId)

        if (old == null) {
            nrDao.insert(entity)
        } else {
            nrDao.update(entity.copy(id = old.id))
        }
    }

    /**
     * LTE 网络查询成功后回填本地
     * 这里只取 data 的第一条
     */
    suspend fun saveLteQueryResp(resp: LteCellQueryResp) {
        val first = resp.data.firstOrNull() ?: return
        val eci = first.eci ?: return

        val entity = first.toLteEntity()
        val old = lteDao.findByEci(eci)

        if (old == null) {
            lteDao.insert(entity)
        } else {
            lteDao.update(entity.copy(id = old.id))
        }
    }

    suspend fun saveNrSiteQueryResp(resp: NrSiteQueryResp) {
        val first = resp.data.firstOrNull() ?: return

        val gcellId = first.gcell_id?.trim().orEmpty()
        if (gcellId.isBlank()) return

        val entity = first.toNrEntity(gcellId)
        val old = nrDao.findByGcellId(gcellId)

        if (old == null) {
            nrDao.insert(entity)
        } else {
            nrDao.update(entity.copy(id = old.id))
        }
    }

    suspend fun saveLteSiteQueryResp(resp: LteSiteQueryResp) {
        val first = resp.data.firstOrNull() ?: return
        val eci = first.eci ?: return

        val entity = first.toLteEntity()
        val old = lteDao.findByEci(eci)

        if (old == null) {
            lteDao.insert(entity)
        } else {
            lteDao.update(entity.copy(id = old.id))
        }
    }

    suspend fun evictLteByEci(eci: Long?): Int {
        if (eci == null) return 0
        return lteDao.deleteByEci(eci)
    }

    suspend fun evictNrByGcellId(gcellId: String?): Int {
        val key = gcellId?.trim().orEmpty()
        if (key.isBlank()) return 0
        return nrDao.deleteByGcellId(key)
    }

    suspend fun clearAll() {
        nrDao.clearAll()
        lteDao.clearAll()
    }

    suspend fun countNr(): Int = nrDao.count()

    suspend fun countLte(): Int = lteDao.count()
}

data class LocalSaveResult(
    val success: Boolean,
    val inserted: Int,
    val updated: Int,
    val message: String
)

private fun NrCellParam.toEntity(): NrCellParamEntity {
    return NrCellParamEntity(
        gcellId = gcellId.trim(),
        cellName = cellName,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        nrPci = nrPci,
        nrArfcn = nrArfcn,
        nrTac = nrTac,
        siteType = siteType,
        antennaHeight = antennaHeight,
        source = source
    )
}

private fun ImportLteCellParam.toEntity(): LteCellParamEntity {
    return LteCellParamEntity(
        tac = tac,
        pci = pci,
        enodebId = enodeb_id,
        earfcn = earfcn,
        cellId = cell_id,
        eci = eci,
        localCellId = local_cell_id,
        cellName = cell_name,
        sectorId = sector_id,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        siteType = site_type,
        antennaHeight = antenna_height,
        source = source,
        createdAt = created_at
    )
}

private fun NrCellParamEntity.toNrQueryResp(): NrCellQueryResp {
    return NrCellQueryResp(
        match_level = "local",
        count = 1,
        total_count = 1,
        truncated = false,
        data = listOf(
            NrCellParamItem(
                id = id,
                gcell_id = gcellId,
                cell_name = cellName,
                longitude = longitude,
                latitude = latitude,
                azimuth = azimuth,
                nr_pci = nrPci,
                nr_arfcn = nrArfcn,
                nr_tac = nrTac,
                site_type = siteType,
                antenna_height = antennaHeight,
                source = source,
                created_at = null,
                updated_at = null
            )
        ),
        reason = null
    )
}

private fun LteCellParamEntity.toLteQueryResp(): LteCellQueryResp {
    return LteCellQueryResp(
        match_level = "local",
        count = 1,
        data = listOf(
            LteCellParamRow(
                id = id,
                tac = tac,
                pci = pci,
                enodeb_id = enodebId,
                cell_id = cellId,
                eci = eci,
                earfcn = earfcn,
                cell_name = cellName,
                sector_id = sectorId,
                longitude = longitude,
                latitude = latitude,
                azimuth = azimuth,
                site_type = siteType,
                antenna_height = antennaHeight,
                source = source
            )
        )
    )
}

private fun LteCellQueryResp.toLteSiteQueryResp(): LteSiteQueryResp {
    val rows = data.map { row ->
        com.nvmex.networkhelper.network.map.LteCellParam(
            id = row.id,
            tac = row.tac,
            pci = row.pci,
            enodeb_id = row.enodeb_id,
            earfcn = row.earfcn,
            cell_id = row.cell_id,
            eci = row.eci,
            local_cell_id = null,
            cell_name = row.cell_name,
            sector_id = row.sector_id,
            longitude = row.longitude,
            latitude = row.latitude,
            azimuth = row.azimuth,
            site_type = row.site_type,
            antenna_height = row.antenna_height,
            source = row.source,
            created_at = null
        )
    }

    return LteSiteQueryResp(
        match_level = match_level,
        anchor = rows.firstOrNull(),
        enodeb_id = rows.firstOrNull()?.enodeb_id,
        sector_count = rows.size,
        count = rows.size,
        truncated = false,
        data = rows
    )
}

private fun NrCellParamItem.toNrEntity(gcellId: String): NrCellParamEntity {
    return NrCellParamEntity(
        gcellId = gcellId,
        cellName = cell_name,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        nrPci = nr_pci,
        nrArfcn = nr_arfcn,
        nrTac = nr_tac,
        siteType = site_type,
        antennaHeight = antenna_height,
        source = source
    )
}

private fun LteCellParamRow.toLteEntity(): LteCellParamEntity {
    return LteCellParamEntity(
        tac = tac,
        pci = pci,
        enodebId = enodeb_id,
        earfcn = earfcn,
        cellId = cell_id,
        eci = eci,
        localCellId = null,
        cellName = cell_name,
        sectorId = sector_id,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        siteType = site_type,
        antennaHeight = antenna_height,
        source = source,
        createdAt = null
    )
}

private fun com.nvmex.networkhelper.network.map.LteCellParam.toLteEntity(): LteCellParamEntity {
    return LteCellParamEntity(
        tac = tac,
        pci = pci,
        enodebId = enodeb_id,
        earfcn = earfcn,
        cellId = cell_id,
        eci = eci,
        localCellId = local_cell_id,
        cellName = cell_name,
        sectorId = sector_id,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        siteType = site_type,
        antennaHeight = antenna_height,
        source = source,
        createdAt = created_at
    )
}
