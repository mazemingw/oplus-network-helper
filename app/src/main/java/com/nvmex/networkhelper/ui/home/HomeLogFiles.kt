package com.nvmex.networkhelper.ui.home

import com.nvmex.networkhelper.model.network.*
import com.nvmex.networkhelper.util.records.CsvTable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object HomeLogFiles {
    val persistence = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private fun writeAtomic(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".${UUID.randomUUID()}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        try {
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
    private fun directory(root: File) = File(root, "home_logs").apply { mkdirs() }
    private fun migrate(root: File) {
        val marker = File(directory(root), ".legacy_migrated")
        if (marker.exists()) return
        val old = File(root, "home_log_recording.json")
        val target = File(directory(root), "legacy_recording.json")
        if (old.exists() && !target.exists()) old.copyTo(target, overwrite = false)
        marker.writeText("1")
    }
    fun list(root: File): List<File> { migrate(root); return directory(root).listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() } }
    fun save(root: File, samples: List<HomeLogSample>): File {
        require(samples.isNotEmpty()) { "日志为空" }
        migrate(root)
        val text = samplesToJson(samples).toString()
        val file = File(directory(root), "home_${samples.first().ts}_${UUID.randomUUID()}.json")
        writeAtomic(file, text)
        writeAtomic(File(root, "home_log_recording.json"), text)
        return file
    }
    fun read(file: File): List<HomeLogSample> = samplesFromJson(JSONArray(file.readText(Charsets.UTF_8)))
    fun delete(root: File, file: File) {
        require(file.canonicalFile.parentFile == directory(root).canonicalFile) { "日志路径无效" }
        val latest = File(root, "home_log_recording.json")
        if (latest.exists() && latest.readText(Charsets.UTF_8) == file.readText(Charsets.UTF_8)) check(latest.delete()) { "无法删除回读副本" }
        check(file.delete()) { "无法删除日志" }
    }
    fun csv(samples: List<HomeLogSample>): String {
        val headers = listOf("timestamp") + (1..2).flatMap { panelCsvHeaders("Sub$it") + qosCsvHeaders("Sub$it") } + globalCsvHeaders() + "sample_json"
        return CsvTable.encode(headers, samples.map { s ->
            val panels = s.panelBySubId.values.sortedBy { it.subId }.take(2)
            listOf(s.ts.toString()) + (0..1).flatMap { i -> panelCsvValues(panels.getOrNull(i)) + qosCsvValues(panels.getOrNull(i)?.subId?.let { s.qosBySubId[it] }) } +
                globalCsvValues(s.globalEvent) + samplesToJson(listOf(s)).getJSONObject(0).toString()
        })
    }
    fun importText(text: String): List<HomeLogSample> {
        if (text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("[")) return samplesFromJson(JSONArray(text.trimStart('\uFEFF'))).also { require(it.isNotEmpty()) { "日志为空" } }
        val rows = CsvTable.decode(text)
        require(rows.isNotEmpty() && rows.first().containsKey("timestamp")) { "缺少 timestamp 列" }
        return rows.mapIndexed { index, row ->
            val ts = row["timestamp"]?.toLongOrNull()?.takeIf { it > 0 } ?: error("第 ${index + 2} 行时间戳无效")
            val json = row["sample_json"]?.takeIf { it.isNotBlank() }
            if (json != null) samplesFromJson(JSONArray().put(JSONObject(json))).single().copy(ts = ts)
            else {
                val slotIds = (1..2).mapNotNull { slot -> row["Sub${slot}_subId"]?.toIntOrNull()?.let { slot to it } }
                val panels = slotIds.map { (slot, id) ->
                    val p = "Sub${slot}_"
                    val obj = JSONObject()
                    val mapping = mapOf("operator" to "operatorName", "dataNetwork" to "dataNetworkType", "linkDownKbps" to "linkDownstreamKbps", "linkUpKbps" to "linkUpstreamKbps")
                    panelCsvHeaders("Sub$slot").forEach { column ->
                        val key = column.removePrefix(p)
                        row[column]?.takeIf { it.isNotBlank() }?.let { obj.put(mapping[key] ?: key, it) }
                    }
                    id to panelFromJson(obj).copy(subId = id)
                }.toMap()
                require(panels.isNotEmpty()) { "第 ${index + 2} 行没有 SIM 数据" }
                val qos = slotIds.mapNotNull { (slot, id) ->
                    val obj = JSONObject()
                    qosCsvHeaders("Sub$slot").forEach { key -> row[key]?.takeIf { it.isNotBlank() }?.let { obj.put(key.substringAfter("_qos_"), it) } }
                    if (obj.length() == 0) null else id to QosData.fromJson(obj.toString()).copy(timestamp = ts, subId = id)
                }.toMap()
                val global = JSONObject()
                globalCsvHeaders().forEach { key -> row[key]?.takeIf { it.isNotBlank() }?.let { global.put(key.removePrefix("global_"), it) } }
                HomeLogSample(ts, panels, qos, if (global.length() == 0) null else GlobalQosEvent.fromJson(global.toString()).copy(timestamp = ts))
            }
        }
    }
}
