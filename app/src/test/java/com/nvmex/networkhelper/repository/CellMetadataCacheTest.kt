package com.nvmex.networkhelper.repository

import com.nvmex.networkhelper.db.dao.LteCellParamDao
import com.nvmex.networkhelper.db.dao.NrCellParamDao
import com.nvmex.networkhelper.db.entity.LteCellParamEntity
import com.nvmex.networkhelper.db.entity.ManagedCell
import com.nvmex.networkhelper.db.entity.ManagedCellDao
import com.nvmex.networkhelper.model.menu.LteCellParamRow
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.menu.LteCellQueryResp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class CellMetadataCacheTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> fake(type: Class<T>, handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> handler(method.name, args ?: emptyArray()) } as T

    @Test fun metadataSurvivesCloudCachingSiteConversionAndSavingAsALocalRecord() = runBlocking {
        var cached: LteCellParamEntity? = null
        var own: ManagedCell? = null
        val lte = fake(LteCellParamDao::class.java) { method, args ->
            when (method) {
                "findByEci", "findFirstByParams" -> cached
                "insert" -> { cached = (args[0] as LteCellParamEntity).copy(id = 1); 1L }
                "update" -> { cached = args[0] as LteCellParamEntity; Unit }
                else -> error("Unexpected LTE DAO call: $method")
            }
        }
        val managed = fake(ManagedCellDao::class.java) { method, args ->
            when (method) {
                "find" -> own
                "put" -> { own = (args[0] as List<*>).single() as ManagedCell; Unit }
                else -> error("Unexpected managed DAO call: $method")
            }
        }
        val nr = fake(NrCellParamDao::class.java) { method, _ -> error("Unexpected NR DAO call: $method") }
        val repository = CellParamLocalRepository(nr, lte, managed)
        val request = LteCellQueryBody(tac = 9, earfcn = 38650, eci = 25601)
        val row = LteCellParamRow(eci = 25601, tac = 9, earfcn = 38650, site_type = "室分",
            extra_info = "地址=A\\,B楼,厂商=华为", station_type = "nb-iot")
        repository.saveLteQueryResp(LteCellQueryResp("primary_eci", 1, listOf(row)))
        assertEquals(row.extra_info, repository.queryLte(request)!!.data.single().extra_info)
        val site = repository.queryLteSite(request)!!
        assertEquals("nb-iot", site.data.single().station_type)
        repository.saveLteSiteQueryResp(site.copy(data = site.data.map { it.copy(extra_info = "地址=新地址") }))
        assertEquals("地址=新地址", repository.queryLte(request)!!.data.single().extra_info)
        repository.saveManaged(ManagedCell.from(cached!!).copy(name = "本地室分"))
        val local = repository.queryLte(request)!!
        assertEquals("local_user", local.match_level)
        assertEquals("地址=新地址", local.data.single().extra_info)
        assertEquals("nb-iot", local.data.single().station_type)
    }
}
