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
    private val lteDao: LteCellParamDao,
    private val managed: com.nvmex.networkhelper.db.entity.ManagedCellDao
) {

    /**
     * 导入 NR 文件后写入本地库
     * 唯一键：gcellId
     */
    suspend fun saveNr(items: List<NrCellParam>): LocalSaveResult = saveImported(items.map {
        com.nvmex.networkhelper.db.entity.ManagedCell.from(it.toEntity())
    })

    suspend fun saveLte(items: List<ImportLteCellParam>): LocalSaveResult = saveImported(items.filter { it.eci != null }.map {
        com.nvmex.networkhelper.db.entity.ManagedCell.from(it.toEntity())
    })

    private suspend fun saveImported(items: List<com.nvmex.networkhelper.db.entity.ManagedCell>): LocalSaveResult {
        val rows = items.distinctBy { it.rat to it.ci }
        require(rows.isNotEmpty()) { "没有可写入的本地工参" }
        rows.forEachIndexed { index, row ->
            try { row.validate(requireName = false) } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${index + 1} 条工参：${error.message}")
            }
        }
        val before = managed.count()
        managed.put(rows)
        val inserted = managed.count() - before
        return LocalSaveResult(true, inserted, rows.size - inserted, "本地写入完成：新增 $inserted 条，更新 ${rows.size - inserted} 条")
    }

    suspend fun listManaged(search: String = "", limit: Int = 100, offset: Int = 0) = managed.list(search, limit, offset)
    suspend fun findManaged(rat: String, ci: String) = managed.find(rat, ci)
    suspend fun countManaged() = managed.count()
    suspend fun listLegacy(search: String, limit: Int) = (lteDao.listLegacy(search, limit).filter { it.eci != null }.map {
        com.nvmex.networkhelper.db.entity.ManagedCell.from(it)
    } + nrDao.listLegacy(search, limit).map { com.nvmex.networkhelper.db.entity.ManagedCell.from(it) }).sortedByDescending { it.updatedAt }.take(limit)
    suspend fun countLegacy() = lteDao.count() + nrDao.count()
    suspend fun deleteLegacy(rat: String, ci: String) {
        if (rat == "LTE") ci.toLongOrNull()?.let { lteDao.deleteByEci(it) } else nrDao.deleteByGcellId(ci)
    }
    suspend fun saveManaged(row: com.nvmex.networkhelper.db.entity.ManagedCell, replaceLegacy: Boolean = false) {
        row.validate()
        managed.put(listOf(row.copy(updatedAt = System.currentTimeMillis())))
        if (replaceLegacy) deleteLegacy(row.rat, row.ci)
    }
    suspend fun deleteManaged(rat: String, ci: String) = managed.delete(rat, ci)

    /**
     * NR 查询：优先本地
     * 优先 gcellId，其次 nrTac + nrArfcn + nrPci
     */
    suspend fun queryNr(body: NrQueryReq): NrCellQueryResp? {
        val gcellId = body.gcellId?.trim().orEmpty()
        val own = if (gcellId.isNotBlank()) managed.find("NR", gcellId)
            else managed.match("NR", body.nrTac, body.nrArfcn, body.nrPci)
        if (own != null) return own.nr().toNrQueryResp().copy(match_level = "local_user")

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
        val own = if (body.eci != null) managed.find("LTE", body.eci.toString())
            else managed.match("LTE", body.tac, body.earfcn, body.pci)
        if (own != null) return own.lte().toLteQueryResp().copy(match_level = "local_user")
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
        createdAt = created_at,
        extraInfo = extra_info,
        stationType = station_type
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
                source = source,
                extra_info = extraInfo,
                station_type = stationType
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
            created_at = null,
            extra_info = row.extra_info,
            station_type = row.station_type
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
        createdAt = null,
        extraInfo = extra_info,
        stationType = station_type
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
        createdAt = created_at,
        extraInfo = extra_info,
        stationType = station_type
    )
}
