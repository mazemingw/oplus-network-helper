package com.nvmex.networkhelper.repository

import com.nvmex.networkhelper.db.dao.*
import com.nvmex.networkhelper.db.entity.*
import com.nvmex.networkhelper.model.menu.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LocalCellOwnershipTest {
    private inline fun <reified T> fake(noinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T
    private class Store {
        val own = linkedMapOf<Pair<String, String>, ManagedCell>()
        val cached = linkedMapOf<Long, LteCellParamEntity>()
        var cacheWrites = 0
        val managed: ManagedCellDao = fakeDao { name, a -> when (name) {
            "put" -> { @Suppress("UNCHECKED_CAST") val rows = a[0] as List<ManagedCell>; rows.forEach { own[it.rat to it.ci] = it }; Unit }
            "find" -> own[a[0] as String to a[1] as String]
            "match" -> own.values.firstOrNull { it.rat == a[0] && it.tac == a[1] && it.arfcn == a[2] && it.pci == a[3] }
            "count" -> own.size
            "list" -> own.values.toList()
            "delete" -> { own.remove(a[0] as String to a[1] as String); Unit }
            else -> error(name)
        } }
        val lte: LteCellParamDao = fakeDao { name, a -> when (name) {
            "findByEci" -> cached[a[0] as Long]
            "findFirstByParams" -> null
            "insert" -> { val row = a[0] as LteCellParamEntity; cached[row.eci!!] = row; cacheWrites++; 1L }
            "update" -> { val row = a[0] as LteCellParamEntity; cached[row.eci!!] = row; cacheWrites++; Unit }
            "deleteByEci" -> { cached.remove(a[0] as Long); cacheWrites++; 1 }
            "clearAll" -> { cached.clear(); cacheWrites++; Unit }
            "count" -> cached.size
            "listLegacy" -> cached.values.toList()
            else -> error("Unexpected cache mutation: $name")
        } }
        val nr: NrCellParamDao = fakeDao { name, _ -> when (name) {
            "findByGcellId", "findFirstByTacArfcnPci" -> null
            "count" -> 0
            "listLegacy" -> emptyList<NrCellParamEntity>()
            "clearAll" -> Unit
            else -> error("Unexpected NR cache mutation: $name")
        } }
        val repository = CellParamLocalRepository(nr, lte, managed)
        companion object {
            private inline fun <reified T> fakeDao(noinline call: (String, Array<out Any?>) -> Any?): T =
                Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args ?: emptyArray()) } as T
        }
    }
    @Test fun localImportEditAndDeleteUseOnlyTheUserTableAndLeaveCloudCopiesUntouched() = runBlocking {
        val store = Store()
        store.cached[25601] = LteCellParamEntity(eci = 25601, cellName = "云端缓存", tac = 9, earfcn = 1650)
        store.repository.saveLte(listOf(com.nvmex.networkhelper.model.cellparam.LteCellParam(eci = 25601, cell_name = "我导入的站", tac = 9, earfcn = 1650)))
        assertEquals(0, store.cacheWrites)
        val query = LteCellQueryBody(tac = 9, earfcn = 1650, eci = 25601)
        assertEquals("我导入的站", store.repository.queryLte(query)!!.data.single().cell_name)
        assertEquals("local_user", store.repository.queryLte(query)!!.match_level)
        val own = store.repository.findManaged("LTE", "25601")!!
        store.repository.saveManaged(own.copy(name = "修改后的本地站"))
        assertEquals("修改后的本地站", store.repository.queryLte(query)!!.data.single().cell_name)
        store.repository.deleteManaged("LTE", "25601")
        assertEquals("云端缓存", store.repository.queryLte(query)!!.data.single().cell_name)
        assertEquals(0, store.cacheWrites)
    }
    @Test fun refreshingCloudCacheCannotOverwriteUserMaintainedRecords() = runBlocking {
        val store = Store()
        store.repository.saveManaged(ManagedCell("LTE", "25601", "本地站", tac = 9, arfcn = 1650))
        store.repository.saveLteQueryResp(LteCellQueryResp(match_level = "primary_eci", count = 1,
            data = listOf(LteCellParamRow(eci = 25601, cell_name = "新的云端站", tac = 9, earfcn = 1650))))
        assertEquals("本地站", store.repository.findManaged("LTE", "25601")!!.name)
        assertEquals("本地站", store.repository.queryLte(LteCellQueryBody(tac = 9, earfcn = 1650, eci = 25601))!!.data.single().cell_name)
        assertEquals("新的云端站", store.cached[25601]!!.cellName)
    }
    @Test fun invalidBulkImportDoesNotPartiallyWriteLocalRecords() = runBlocking {
        val store = Store()
        val failure = runCatching { store.repository.saveLte(listOf(
            com.nvmex.networkhelper.model.cellparam.LteCellParam(eci = 25601, cell_name = "正常"),
            com.nvmex.networkhelper.model.cellparam.LteCellParam(eci = -1, cell_name = "非法"))) }
        assertTrue(failure.isFailure)
        assertTrue(store.own.isEmpty())
        assertEquals(0, store.cacheWrites)
    }

    @Test fun legacyCustomSourceCanBeAdoptedAndDeletedWithoutReappearingFromTheOldLocalTable() = runBlocking {
        val store = Store()
        store.cached[25601] = LteCellParamEntity(eci = 25601, cellName = "旧自定义来源", source = "用户自采")
        val legacy = store.repository.listLegacy("", 100).single()
        store.repository.saveManaged(legacy.copy(name = "迁入我的工参"), replaceLegacy = true)
        assertTrue(store.cached.isEmpty())
        assertEquals("迁入我的工参", store.repository.findManaged("LTE", "25601")!!.name)
        store.repository.deleteManaged("LTE", "25601")
        assertNull(store.repository.queryLte(LteCellQueryBody(tac = 9, earfcn = 1650, eci = 25601)))
    }

    @Test fun clearingQueryCacheDoesNotRemoveUserOwnedImports() = runBlocking {
        val store = Store()
        store.repository.saveManaged(ManagedCell("LTE", "25601", "我的站"))
        store.repository.clearAll()
        assertEquals("我的站", store.repository.findManaged("LTE", "25601")!!.name)
    }
}
