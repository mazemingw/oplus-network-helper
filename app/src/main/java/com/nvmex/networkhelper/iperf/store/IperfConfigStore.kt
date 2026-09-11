package com.nvmex.networkhelper.iperf.store


import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.nvmex.networkhelper.iperf.client.IperfClientConfig
import com.nvmex.networkhelper.iperf.client.IperfProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "iperf_client")

class IperfConfigStore(private val ctx: Context) {

    private object K {
        val serverHost = stringPreferencesKey("serverHost")
        val port = intPreferencesKey("port")
        val protocol = stringPreferencesKey("protocol")
        val durationSec = intPreferencesKey("durationSec")
        val parallel = intPreferencesKey("parallel")
        val reverse = booleanPreferencesKey("reverse")

        val udpBandwidth = stringPreferencesKey("udpBandwidth")
        val udpPacketLen = intPreferencesKey("udpPacketLen")

        val omitSec = intPreferencesKey("omitSec")
        val mss = intPreferencesKey("mss")
        val tos = intPreferencesKey("tos")
        val bindAddress = stringPreferencesKey("bindAddress")
        val extraArgs = stringPreferencesKey("extraArgs") // 空格分隔
    }

    fun observe(): Flow<IperfClientConfig> = ctx.dataStore.data.map { p ->
        IperfClientConfig(
            serverHost = p[K.serverHost] ?: "",
            port = p[K.port] ?: 5201,
            protocol = when (p[K.protocol]) {
                IperfProtocol.UDP.name -> IperfProtocol.UDP
                else -> IperfProtocol.TCP
            },
            durationSec = p[K.durationSec] ?: 10,
            parallel = p[K.parallel] ?: 1,
            reverse = p[K.reverse] ?: false,

            udpBandwidth = p[K.udpBandwidth] ?: "200M",
            udpPacketLen = p[K.udpPacketLen],

            omitSec = p[K.omitSec] ?: 0,
            mss = p[K.mss],
            tos = p[K.tos],
            bindAddress = p[K.bindAddress]?.ifBlank { null },
            extraArgs = (p[K.extraArgs] ?: "")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        )
    }

    suspend fun save(cfg: IperfClientConfig) {
        ctx.dataStore.edit { e ->
            e[K.serverHost] = cfg.serverHost
            e[K.port] = cfg.port
            e[K.protocol] = cfg.protocol.name
            e[K.durationSec] = cfg.durationSec
            e[K.parallel] = cfg.parallel
            e[K.reverse] = cfg.reverse

            e[K.udpBandwidth] = cfg.udpBandwidth
            cfg.udpPacketLen?.let { e[K.udpPacketLen] = it } ?: e.remove(K.udpPacketLen)

            e[K.omitSec] = cfg.omitSec
            cfg.mss?.let { e[K.mss] = it } ?: e.remove(K.mss)
            cfg.tos?.let { e[K.tos] = it } ?: e.remove(K.tos)
            e[K.bindAddress] = cfg.bindAddress ?: ""
            e[K.extraArgs] = cfg.extraArgs.joinToString(" ")
        }
    }
}
