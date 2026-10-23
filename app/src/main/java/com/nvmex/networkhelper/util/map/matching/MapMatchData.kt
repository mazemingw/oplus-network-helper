package com.nvmex.networkhelper.util.map.matching

import android.content.Context
import android.net.Uri
import com.nvmex.networkhelper.ui.settings.AppSettingsStore
import java.io.File

object MapMatchData {
    const val ASSET = "map_matching/beijing_sample.geojson"
    const val REVISION = "map_match_data_revision"
    private fun local(context: Context) = File(context.filesDir, "map_matching.geojson")
    fun load(context: Context): RouteNetwork = RouteNetwork.parse(if (local(context).exists()) local(context).readText()
        else context.assets.open(ASSET).bufferedReader().use { it.readText() })
    fun import(context: Context, uri: Uri): RouteNetwork {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= RouteNetwork.MAX_BYTES) { "线路文件超过 2 MB" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: error("无法读取线路")
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF')
        val network = RouteNetwork.parse(text)
        val temp = File(context.filesDir, "map_matching.import.tmp")
        temp.writeText(text)
        java.nio.file.Files.move(temp.toPath(), local(context).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        changed(context)
        return network
    }
    fun restore(context: Context) { local(context).takeIf { it.exists() }?.let { check(it.delete()) }; changed(context) }
    private fun changed(context: Context) { AppSettingsStore.preferences(context).edit().putLong(REVISION, System.nanoTime()).apply() }
}
